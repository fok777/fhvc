package com.termux.zerocore.ai.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * MCP Streamable-HTTP 客户端（MCP 2025-03-26+ 传输层）。
 *
 *   - Accept 强制写入 `application/json, text/event-stream`，用户自定义头不能覆盖；
 *   - initialize 只做一次，有状态服务端会拒绝二次握手，Mcp-Session-Id 之后每请求回传；
 *   - 只在连接阶段重试一次（此时还没发出内容，不会导致工具被重复执行），
 *     发出之后的失败一律不重试，因为 tools/call 可能不是幂等的；
 *   - HTTP 401 转成 AUTH_REQUIRED。
 *
 * 阻塞式调用，必须在工作线程执行。
 */
class ZtMcpClient(
    val serverName: String,
    private val url: String,
    /** 静态请求头（如 Authorization）。 */
    private val headers: Map<String, String> = emptyMap(),
    private val http: OkHttpClient = defaultClient()
) {

    companion object {
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 300L
        private const val WRITE_TIMEOUT_S = 30L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val lock = Any()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var negotiatedVersion: String = ZtMcpJsonRpc.PROTOCOL_VERSION

    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val id = ZtMcpJsonRpc.nextId()
            val result = post(
                ZtMcpJsonRpc.request(id, "initialize", ZtMcpJsonRpc.initializeParams()),
                id
            )
            val serverVersion = result?.optString("protocolVersion", "") ?: ""
            // 服务端回的是"它自己说的版本"，规范允许客户端继续。记录并在后续请求回传。
            if (serverVersion.isNotEmpty()) negotiatedVersion = serverVersion

            // notifications/initialized：发了不管（202 也算成功）。
            runCatching { post(ZtMcpJsonRpc.notification("notifications/initialized"), null) }
            initialized = true
        }
    }

    /** `tools/list`，返回原始 tool 对象数组。 */
    fun listTools(): JSONArray {
        ensureInitialized()
        val id = ZtMcpJsonRpc.nextId()
        val result = post(ZtMcpJsonRpc.request(id, "tools/list", JSONObject()), id)
            ?: throw ZtMcpJsonRpc.McpError(
                ZtMcpJsonRpc.McpError.PROTOCOL_ERROR,
                "tools/list returned no result"
            )
        return result.optJSONArray("tools") ?: JSONArray()
    }

    /** `tools/call`，返回原始 result（content 数组 + isError）。 */
    fun callTool(tool: String, arguments: JSONObject): JSONObject {
        ensureInitialized()
        val id = ZtMcpJsonRpc.nextId()
        val params = JSONObject().apply {
            put("name", tool)
            put("arguments", arguments)
        }
        return post(ZtMcpJsonRpc.request(id, "tools/call", params), id)
            ?: throw ZtMcpJsonRpc.McpError(
                ZtMcpJsonRpc.McpError.PROTOCOL_ERROR,
                "tools/call returned no result"
            )
    }

    fun close() {
        synchronized(lock) {
            initialized = false
            sessionId = null
        }
    }

    /**
     * 发一条 JSON-RPC 并返回对应 id 的 result；[wantId] 为 null 表示通知 / 202。
     */
    private fun post(payload: JSONObject, wantId: Int?): JSONObject? {
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        var connectAttempts = 0
        while (true) {
            connectAttempts++
            val builder = Request.Builder().url(url).post(body)
            // 先放用户头，再用协议要求的头覆盖 —— 用户设置的 accept/content-type
            // 不能把握手弄坏。
            for ((k, v) in headers) {
                if (k.equals("accept", true) || k.equals("content-type", true)) continue
                builder.header(k, v)
            }
            builder.header("Content-Type", "application/json")
            builder.header("Accept", "application/json, text/event-stream")
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            if (initialized) builder.header("MCP-Protocol-Version", negotiatedVersion)

            try {
                http.newCall(builder.build()).execute().use { resp ->
                    if (resp.code == 401) {
                        throw ZtMcpJsonRpc.McpError(
                            ZtMcpJsonRpc.McpError.AUTH_REQUIRED,
                            "HTTP 401 Unauthorized — 检查 MCP 服务器的凭据配置"
                        )
                    }
                    if (!resp.isSuccessful) {
                        val preview = runCatching { resp.body?.string()?.take(300) ?: "" }
                            .getOrDefault("")
                        throw ZtMcpJsonRpc.McpError(
                            ZtMcpJsonRpc.McpError.SERVER_ERROR,
                            "HTTP ${resp.code}: $preview"
                        )
                    }
                    resp.header("Mcp-Session-Id")?.takeIf { it.isNotEmpty() }?.let {
                        sessionId = it
                    }
                    if (wantId == null || resp.code == 202) return null
                    val ct = resp.header("Content-Type") ?: ""
                    val text = resp.body?.string() ?: ""
                    val reply = ZtMcpJsonRpc.extractResponse(text, ct, wantId)
                        ?: throw ZtMcpJsonRpc.McpError(
                            ZtMcpJsonRpc.McpError.PROTOCOL_ERROR,
                            "no JSON-RPC reply for id=$wantId (content-type=$ct, ${text.length} bytes)"
                        )
                    return ZtMcpJsonRpc.unwrapResult(reply)
                }
            } catch (e: ZtMcpJsonRpc.McpError) {
                throw e
            } catch (e: ConnectException) {
                if (connectAttempts < 2) continue
                throw ZtMcpJsonRpc.McpError(
                    ZtMcpJsonRpc.McpError.CONNECTION_ERROR,
                    "connect failed: ${e.message}"
                )
            } catch (e: SocketTimeoutException) {
                val msg = e.message ?: ""
                if (msg.contains("connect", true) && connectAttempts < 2) continue
                throw ZtMcpJsonRpc.McpError(ZtMcpJsonRpc.McpError.TIMEOUT, "timed out: $msg")
            } catch (e: IOException) {
                throw ZtMcpJsonRpc.McpError(
                    ZtMcpJsonRpc.McpError.CONNECTION_ERROR,
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }
}
