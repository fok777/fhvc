package com.termux.zerocore.ai.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具桥：把 MCP 服务器暴露的工具接进 agent 工具循环。
 *
 *  - INLINE：每个 MCP 工具直接变成一个 function tool，命名 `mcp__<服务器别名>__<工具名>`；
 *  - META：工具太多时只暴露 mcp_list_tools 与 mcp_call_tool 两个元工具，让模型先查再调；
 *  - OFF：未启用。
 *
 * 必须处理的细节：
 *  - OpenAI function name 只允许 [a-zA-Z0-9_-] 且建议 ≤64 字符，截断时要拼哈希避免重名；
 *  - MCP 的 inputSchema 与 OpenAI 的 parameters 兼容，但要剥掉 $schema 并强制 type: object；
 *  - 工具发现结果做内存 TTL 缓存，否则每轮循环都要对所有服务器握手；
 *  - 失败收敛成给模型看的错误文本，不抛异常打断整个 run。
 */
object ZtMcpToolBridge {

    private const val TAG = "ZtMcpToolBridge"

    /** 超过这个数量就降级为 META 模式，避免把请求体撑爆。 */
    const val MAX_INLINE_TOOLS = 48

    /** OpenAI 对 function name 的长度建议上限。 */
    private const val MAX_TOOL_NAME = 64

    private const val DISCOVERY_TTL_MS = 5 * 60 * 1000L

    const val TOOL_LIST = "mcp_list_tools"
    const val TOOL_CALL = "mcp_call_tool"

    data class ResolvedTool(
        val serverId: String,
        val serverAlias: String,
        val toolName: String
    )

    @Volatile
    private var cache: List<Entry> = emptyList()

    @Volatile
    private var cacheAt: Long = 0L

    @Volatile
    private var lastMode: Mode = Mode.OFF

    enum class Mode { OFF, INLINE, META }

    class Entry(
        val generatedName: String,
        val serverId: String,
        val serverAlias: String,
        val toolName: String,
        val description: String,
        val schema: JSONObject,
        val serverUrl: String,
        val headers: Map<String, String>
    )

    // ─── 工具定义注入 ────────────────────────────────────────────────────────

    /**
     * 把 MCP 工具追加进 agent 的 tools 数组。
     * @return 本次采用的模式，供日志与 UI 展示
     */
    fun addTools(tools: JSONArray, forceRefresh: Boolean = false): Mode {
        if (!ZtMcpServerStore.isEnabled()) {
            lastMode = Mode.OFF
            return Mode.OFF
        }
        val entries = discover(forceRefresh)
        if (entries.isEmpty()) {
            lastMode = Mode.OFF
            return Mode.OFF
        }

        if (entries.size <= MAX_INLINE_TOOLS) {
            entries.forEach { tools.put(functionTool(it)) }
            lastMode = Mode.INLINE
        } else {
            tools.put(metaListTool(entries))
            tools.put(metaCallTool())
            lastMode = Mode.META
        }
        android.util.Log.i(TAG, "mcp mode=${lastMode.name} tools=${entries.size}")
        return lastMode
    }

    private fun functionTool(entry: Entry): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", entry.generatedName)
                .put("description", entry.description.ifBlank { entry.toolName })
                .put("parameters", entry.schema)
        )

    private fun metaListTool(entries: List<Entry>): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", TOOL_LIST)
                .put(
                    "description",
                    "List tools exposed by connected MCP servers. " +
                        "${entries.size} tools available across " +
                        "${entries.map { it.serverAlias }.distinct().size} servers. " +
                        "Call this before mcp_call_tool to discover exact names and arguments."
                )
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "server",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Optional server alias to filter by.")
                            )
                        )
                        .put("required", JSONArray())
                )
        )

    private fun metaCallTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", TOOL_CALL)
                .put("description", "Call a tool on an MCP server. Use mcp_list_tools first.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "tool",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Tool name as reported by mcp_list_tools.")
                                )
                                .put(
                                    "arguments",
                                    JSONObject()
                                        .put("type", "object")
                                        .put("description", "Arguments object for the tool.")
                                )
                        )
                        .put("required", JSONArray().put("tool"))
                )
        )

    // ─── 工具发现 ────────────────────────────────────────────────────────────

    @Synchronized
    fun discover(forceRefresh: Boolean = false): List<Entry> {
        if (!forceRefresh &&
            cache.isNotEmpty() &&
            System.currentTimeMillis() - cacheAt < DISCOVERY_TTL_MS
        ) {
            return cache
        }
        val out = ArrayList<Entry>()
        for (server in ZtMcpServerStore.enabledServers()) {
            val alias = ZtMcpServerStore.aliasOf(server)
            var client: ZtMcpClient? = null
            try {
                client = ZtMcpClient(server.name.ifBlank { server.id }, server.url, server.effectiveHeaders())
                val raw = client.listTools()
                for (i in 0 until raw.length()) {
                    val t = raw.optJSONObject(i) ?: continue
                    val name = t.optString("name", "").trim()
                    if (name.isEmpty()) continue
                    out.add(
                        Entry(
                            generatedName = generatedName(alias, name),
                            serverId = server.id,
                            serverAlias = alias,
                            toolName = name,
                            description = prefixServer(alias, t.optString("description", "")),
                            schema = sanitizeSchema(t.optJSONObject("inputSchema")),
                            serverUrl = server.url,
                            headers = server.effectiveHeaders()
                        )
                    )
                }
                ZtMcpServerStore.updateStatus(server.id, raw.length(), null)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                android.util.Log.w(TAG, "discover failed for ${server.name}: $msg")
                ZtMcpServerStore.updateStatus(server.id, 0, msg)
            } finally {
                runCatching { client?.close() }
            }
        }
        cache = out
        cacheAt = System.currentTimeMillis()
        return out
    }

    @Synchronized
    fun invalidate() {
        cache = emptyList()
        cacheAt = 0L
    }

    @Synchronized
    fun cachedCount(): Int = cache.size

    @Synchronized
    fun cachedServers(): List<String> = cache.map { it.serverAlias }.distinct()

    // ─── 工具调用 ────────────────────────────────────────────────────────────

    /** 判断一个工具名是否属于 MCP（供 executor 分发）。 */
    fun isMcpTool(name: String): Boolean =
        name.startsWith("mcp__") || name == TOOL_LIST || name == TOOL_CALL

    /**
     * 执行 MCP 工具。任何失败都收敛成文本返回，不抛异常 —— 否则一次 MCP 服务器
     * 超时就会把整个 agent run 打断。
     */
    fun execute(name: String, arguments: String): String {
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }
            .getOrElse { JSONObject() }

        return when (name) {
            TOOL_LIST -> executeList(args)
            TOOL_CALL -> executeCall(args.optString("tool", ""), args.optJSONObject("arguments"))
            else -> {
                val entry = cache.firstOrNull { it.generatedName == name }
                    ?: return "Error: unknown MCP tool `$name`. " +
                        "MCP tools may have refreshed — call $TOOL_LIST to see the current set."
                executeCall(entry.toolName, args, entry)
            }
        }
    }

    private fun executeList(args: JSONObject): String {
        val filter = args.optString("server", "").trim().lowercase()
        val entries = discover().filter {
            filter.isEmpty() || it.serverAlias.contains(filter) || it.serverId.contains(filter)
        }
        if (entries.isEmpty()) {
            return "No MCP tools available. Servers configured: " +
                "${ZtMcpServerStore.enabledServers().size}. Check Settings → MCP."
        }
        val sb = StringBuilder()
        val grouped = entries.groupBy { it.serverAlias }
        sb.append("${entries.size} MCP tool(s) across ${grouped.size} server(s):\n\n")
        grouped.forEach { (alias, list) ->
            sb.append("## $alias\n")
            list.forEach { e ->
                val desc = e.description.removePrefix("[$alias] ").trim()
                sb.append("- ${e.toolName}")
                if (desc.isNotBlank()) sb.append(": ${desc.take(160)}")
                sb.append('\n')
            }
            sb.append('\n')
        }
        sb.append("Call them via $TOOL_CALL with the exact tool name above.")
        return sb.toString().trimEnd()
    }

    private fun executeCall(tool: String, args: JSONObject?, known: Entry? = null): String {
        val name = tool.trim()
        if (name.isEmpty()) return "Error: tool name is required."

        val entry = known ?: discover().firstOrNull {
            it.toolName.equals(name, ignoreCase = true) ||
                it.generatedName.equals(name, ignoreCase = true)
        }
        if (entry == null) {
            return "Error: no MCP tool named `$name`. Call $TOOL_LIST to see available tools."
        }

        var client: ZtMcpClient? = null
        return try {
            client = ZtMcpClient(entry.serverAlias, entry.serverUrl, entry.headers)
            val result = client.callTool(entry.toolName, args ?: JSONObject())
            val outcome = ZtMcpJsonRpc.formatCallResult(result)
            val text = outcome.text.ifBlank { "(MCP tool returned no content)" }
            if (outcome.isError) "Error: $text" else text
        } catch (e: ZtMcpJsonRpc.McpError) {
            // AUTH_REQUIRED 值得单独提示，模型才知道该让用户去改设置而不是重试。
            if (e.code == ZtMcpJsonRpc.McpError.AUTH_REQUIRED) {
                "Error: MCP server `${entry.serverAlias}` requires valid credentials ($e). " +
                    "Ask the user to fix it in Settings → MCP. Do not retry."
            } else {
                "Error: MCP call to `${entry.toolName}` failed [$e.code]: ${e.message}"
            }
        } catch (e: Exception) {
            "Error: MCP call to `${entry.toolName}` failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { client?.close() }
        }
    }

    // ─── 命名与 schema 规范化 ────────────────────────────────────────────────

    /**
     * 生成对模型可见的工具名，并保证：
     *  - 只含 `[a-zA-Z0-9_-]`（OpenAI 限制）；
     *  - 长度 ≤ 64，超了就截断工具名部分**并拼上短哈希**，避免截断后重名。
     */
    fun generatedName(alias: String, toolName: String): String {
        val safeAlias = alias.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safeTool = toolName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val full = "mcp__${safeAlias}__$safeTool"
        if (full.length <= MAX_TOOL_NAME) return full
        val hash = (safeTool.hashCode() and 0xffff).toString(16).padStart(4, '0')
        val keep = MAX_TOOL_NAME - "mcp__${safeAlias}____".length - hash.length
        val truncated = if (keep > 0) safeTool.take(keep) else ""
        return "mcp__${safeAlias}__${truncated}_$hash".take(MAX_TOOL_NAME)
    }

    private fun prefixServer(alias: String, description: String): String {
        val d = description.trim()
        return if (d.isEmpty()) "[$alias]" else "[$alias] $d"
    }

    /** MCP 的 inputSchema → OpenAI parameters。 */
    fun sanitizeSchema(input: JSONObject?): JSONObject {
        val out = JSONObject()
        if (input == null) {
            out.put("type", "object").put("properties", JSONObject())
            return out
        }
        val keys = input.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            // $schema / additionalProperties 之类交给 OpenAI 会报 unknown field。
            if (k == "\$schema" || k.startsWith("\$")) continue
            out.put(k, input.get(k))
        }
        if (!out.has("type")) out.put("type", "object")
        if (!out.has("properties")) out.put("properties", JSONObject())
        return out
    }
}
