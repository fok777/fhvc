package com.termux.zerocore.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 的 JSON-RPC 2.0 线路层。
 *
 * 两个容易踩的点：
 *   - Streamable HTTP 的响应既可能是纯 JSON，也可能是 SSE 事件流，两种都要解析；
 *   - JSON-RPC 的 id 允许字符串或数字，比较时用 toString()，否则服务端把 5 回成 "5" 就匹配不上。
 */
object ZtMcpJsonRpc {

    const val PROTOCOL_VERSION = "2025-06-18"
    const val CLIENT_NAME = "zerotermux"
    const val CLIENT_VERSION = "1.0.0"

    private val idGen = AtomicInteger(1)

    /** 线路级错误，带机器可读的 code，方便 UI 提示"去重新授权"而不是笼统的失败。 */
    class McpError(val code: String, message: String) : Exception("[$code] $message") {
        companion object {
            const val CONNECTION_ERROR = "CONNECTION_ERROR"
            const val TIMEOUT = "TIMEOUT"
            const val AUTH_REQUIRED = "AUTH_REQUIRED"
            const val PROTOCOL_ERROR = "PROTOCOL_ERROR"
            const val SERVER_ERROR = "SERVER_ERROR"
        }
    }

    fun nextId(): Int = idGen.getAndIncrement()

    fun request(id: Int, method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    fun notification(method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }

    fun initializeParams(): JSONObject = JSONObject().apply {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", JSONObject())
        put("clientInfo", JSONObject().apply {
            put("name", CLIENT_NAME)
            put("version", CLIENT_VERSION)
        })
    }

    // ─── 响应解析 ────────────────────────────────────────────────────────────

    /**
     * 从 HTTP 响应体里取出 id 匹配的 JSON-RPC 回复。
     * [contentType] 含 `text/event-stream` 时按 SSE 解析，否则按单个 JSON 文档解析。
     */
    fun extractResponse(body: String, contentType: String, wantId: Int): JSONObject? {
        if (body.isBlank()) return null
        return if (contentType.contains("text/event-stream", ignoreCase = true)) {
            parseSseEvents(body).firstOrNull { isReplyFor(it, wantId) }
        } else {
            runCatching { JSONObject(body) }.getOrNull()?.takeIf { isReplyFor(it, wantId) }
        }
    }

    private fun isReplyFor(obj: JSONObject, wantId: Int): Boolean =
        obj.has("jsonrpc") &&
            obj.opt("id")?.toString() == wantId.toString() &&
            (obj.has("result") || obj.has("error"))

    /**
     * 解析 SSE 流：多行 `data:` 续行按 SSE 规范用 '\n' 拼接，
     * 空行触发 flush，流末尾没有空行时也要 flush 一次（否则丢最后一条）。
     */
    fun parseSseEvents(body: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val dataLines = mutableListOf<String>()
        fun flush() {
            if (dataLines.isEmpty()) return
            val joined = dataLines.joinToString("\n")
            dataLines.clear()
            if (joined.isBlank()) return
            runCatching { JSONObject(joined) }.getOrNull()?.let(out::add)
        }
        for (raw in body.split('\n')) {
            val line = raw.removeSuffix("\r")
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit // 注释 / keepalive
                line.startsWith("data:") ->
                    dataLines.add(line.removePrefix("data:").removePrefix(" "))
            }
        }
        flush()
        return out
    }

    /** 拆信封：有 error 就抛，否则返回 result。 */
    fun unwrapResult(reply: JSONObject): JSONObject {
        val err = reply.optJSONObject("error")
        if (err != null) {
            throw McpError(
                "RPC_${err.opt("code")}",
                err.optString("message", "unknown JSON-RPC error")
            )
        }
        return reply.optJSONObject("result")
            ?: throw McpError(McpError.PROTOCOL_ERROR, "reply has neither result nor error")
    }

    // ─── 结果扁平化 ──────────────────────────────────────────────────────────

    /** `tools/list` 结果 → 给模型看的一行一条摘要。 */
    fun formatToolList(tools: JSONArray): String {
        if (tools.length() == 0) return "Server exposes 0 tools."
        val sb = StringBuilder()
        sb.append(tools.length()).append(" tool(s):\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val name = t.optString("name", "?")
            var desc = t.optString("description", "").trim()
            if (desc.length > 160) desc = desc.substring(0, 160) + "…"
            sb.append("- ").append(name)
            if (desc.isNotEmpty()) sb.append(": ").append(desc.replace('\n', ' '))
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    data class CallOutcome(val text: String, val isError: Boolean)

    /**
     * `tools/call` 结果 → 纯文本。文本片段直接拼接，图片/音频只做摘要
     * （不把 base64 塞进对话，否则一次就把上下文烧穿），
     * `structuredContent` 作为兜底。
     */
    fun formatCallResult(result: JSONObject): CallOutcome {
        val isError = result.optBoolean("isError", false)
        val content = result.optJSONArray("content")
        val parts = mutableListOf<String>()
        if (content != null) {
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> parts.add(c.optString("text", ""))
                    "image" -> parts.add(
                        "[image ${c.optString("mimeType", "image/*")}, " +
                            "${c.optString("data").length} b64 chars — not inlined]"
                    )
                    "audio" -> parts.add("[audio ${c.optString("mimeType", "audio/*")}]")
                    "resource", "resource_link" -> {
                        val embedded = c.optJSONObject("resource")
                        val text = embedded?.optString("text", "")?.takeIf { it.isNotEmpty() }
                            ?: c.optString("uri", "").ifEmpty { null }
                            ?: embedded?.toString()
                        if (text != null) parts.add(text)
                    }
                    else -> parts.add(c.toString())
                }
            }
        }
        var text = parts.joinToString("\n").trim()
        if (text.isEmpty()) {
            val structured = result.optJSONObject("structuredContent")
            text = structured?.toString(2) ?: result.toString(2)
        }
        return CallOutcome(text, isError)
    }
}
