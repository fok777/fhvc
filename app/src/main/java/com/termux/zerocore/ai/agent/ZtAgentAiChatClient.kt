package com.termux.zerocore.ai.agent

import com.example.xh_lib.utils.LogUtils
import com.example.xh_lib.utils.UUtils
import com.termux.zerocore.utils.ZtLocaleStrings
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ZtAgentAiChatClient(
    private val config: ZtAgentAiConfigHelper.ActiveConfig
) {
    interface Listener {
        fun onChunk(text: String)
        fun onError(message: String)
        fun onComplete(fullText: String)
    }

    data class ChatMessage(
        val role: String,
        val content: String? = null,
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null,
        /** 发送该条用户消息时的终端快照；仅持久化与组请求用，不作为独立 API 字段。 */
        val terminalSnapshot: String? = null
    )

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    data class CompletionResult(
        val content: String?,
        val toolCalls: List<ToolCall>,
        val error: String?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 端点的限制档案，决定令牌桶速率与并发上限。 */
    private val profile: ZtEndpointProfile = ZtEndpointProfiles.fromUrl(config.apiUrl)

    private fun limiter(): ZtRateLimiter = limiterCache.getOrPut(profile.id) { ZtRateLimiter(profile) }

    private var activeCall: Call? = null

    fun cancel() {
        activeCall?.cancel()
        activeCall = null
    }

    fun chatCompletionSync(
        messages: List<ChatMessage>,
        tools: JSONArray?
    ): CompletionResult {
        if (config.apiUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            return CompletionResult(
                content = null,
                toolCalls = emptyList(),
                error = ZtLocaleStrings.getString(com.termux.R.string.zt_ai_agent_not_configured)
            )
        }

        val limiter = limiter()
        if (!limiter.acquire()) {
            return throttled(limiter)
        }
        var finalResult: CompletionResult? = null
        try {
            var attempt = 0
            while (finalResult == null) {
                val outcome = callOnce(messages, tools)
                if (outcome.code == 0) {
                    limiter.onSuccess()
                    finalResult = outcome.result
                    break
                }
                limiter.onFailure(outcome.code)
                val retryable = outcome.code == 429 || outcome.code in 500..599
                if (!retryable || !profile.retryOn429 || attempt >= MAX_RETRIES) {
                    finalResult = outcome.result
                    break
                }
                val waitMs = outcome.retryAfterMs ?: limiter.backoffMs(attempt)
                attempt++
                android.util.Log.i(TAG, "retry $attempt/$MAX_RETRIES after ${waitMs}ms (HTTP ${outcome.code})")
                Thread.sleep(waitMs)
                if (limiter.isBreakerOpen()) {
                    finalResult = outcome.result
                    break
                }
            }
        } finally {
            limiter.release()
            activeCall = null
        }
        return finalResult ?: CompletionResult(
            content = null,
            toolCalls = emptyList(),
            error = "Request did not complete"
        )
    }

    private fun throttled(limiter: ZtRateLimiter): CompletionResult {
        val remaining = limiter.breakerRemainingMs()
        val msg = if (remaining > 0) {
            "连续失败过多，已暂停 ${remaining / 1000} 秒后重试"
        } else {
            "已到达端点速率上限（${profile.rpm}/分钟），请求排队超时"
        }
        return CompletionResult(content = null, toolCalls = emptyList(), error = msg)
    }

    private data class Outcome(
        val code: Int,
        val result: CompletionResult,
        val retryAfterMs: Long?
    )

    /** 发一次请求。code == 0 表示成功，其余为 HTTP 状态码（-1 表示网络异常）。 */
    private fun callOnce(
        messages: List<ChatMessage>,
        tools: JSONArray?
    ): Outcome {
        val bodyJson = buildRequestBody(messages, stream = false, tools = tools)
        val body = bodyJson.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(config.apiUrl.trim())
            .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            val call = client.newCall(request)
            activeCall = call
            call.execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Outcome(
                        code = response.code,
                        result = CompletionResult(
                            content = null,
                            toolCalls = emptyList(),
                            error = "HTTP ${response.code}: $responseBody"
                        ),
                        retryAfterMs = parseRetryAfter(response)
                    )
                } else {
                    Outcome(0, parseCompletionResponse(responseBody), null)
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "chatCompletionSync error: $e")
            Outcome(
                code = -1,
                result = CompletionResult(
                    content = null,
                    toolCalls = emptyList(),
                    error = e.message ?: "Request error"
                ),
                retryAfterMs = null
            )
        }
    }

    /** Retry-After 既可能是秒数，也可能是 HTTP-date。 */
    private fun parseRetryAfter(response: okhttp3.Response): Long? {
        val raw = response.header("Retry-After")?.trim() ?: return null
        raw.toLongOrNull()?.let { return (it * 1000).coerceIn(0, 120_000) }
        return runCatching {
            val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(raw) ?: return null
            (date.time - System.currentTimeMillis()).coerceIn(0, 120_000)
        }.getOrNull()
    }

    fun chat(messages: List<ChatMessage>, stream: Boolean, listener: Listener) {
        if (config.apiUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            UUtils.getHandler().post {
                listener.onError(ZtLocaleStrings.getString(com.termux.R.string.zt_ai_agent_not_configured))
                listener.onComplete("")
            }
            return
        }

        val limiter = limiter()
        if (limiter.isBreakerOpen()) {
            postError(listener, "连续失败过多，已暂停 ${limiter.breakerRemainingMs() / 1000} 秒后重试")
            return
        }
        // 流式请求从 UI 发起，最多等一小会儿，取不到令牌也照发，交给服务端兜底。
        val acquired = limiter.acquire(STREAM_ACQUIRE_MS)

        try {
            val bodyJson = buildRequestBody(messages, stream, tools = null)
            val body = bodyJson.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(config.apiUrl.trim())
                .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            activeCall = client.newCall(request)
            activeCall!!.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (acquired) limiter.release()
                    limiter.onFailure(-1)
                    if (call.isCanceled()) return
                    LogUtils.e(TAG, "chat onFailure: $e")
                    postError(listener, e.message ?: "Network error")
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (!response.isSuccessful) {
                        limiter.onFailure(response.code)
                    } else {
                        limiter.onSuccess()
                    }
                    if (call.isCanceled()) {
                        response.close()
                        if (acquired) limiter.release()
                        return
                    }
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errBody = resp.body?.string().orEmpty()
                            postError(listener, "HTTP ${resp.code}: $errBody")
                            return
                        }
                        val source: BufferedSource = resp.body?.source()
                            ?: run {
                                postError(listener, "Empty response")
                                return
                            }
                        val full = StringBuilder()
                        try {
                            var line: String?
                            while (source.readUtf8Line().also { line = it } != null) {
                                if (call.isCanceled()) return
                                val chunk = parseStreamLine(line!!) ?: continue
                                if (chunk == STREAM_DONE) break
                                val content = extractStreamContent(chunk) ?: continue
                                full.append(content)
                                val text = full.toString()
                                UUtils.getHandler().post { listener.onChunk(text) }
                            }
                            if (call.isCanceled()) return
                            val result = full.toString()
                            UUtils.getHandler().post { listener.onComplete(result) }
                        } catch (e: Exception) {
                            if (call.isCanceled()) return
                            LogUtils.e(TAG, "chat parse error: $e")
                            postError(listener, e.message ?: "Parse error")
                        } finally {
                            if (acquired) limiter.release()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            LogUtils.e(TAG, "chat error: $e")
            postError(listener, e.message ?: "Request error")
        }
    }

    private fun postError(listener: Listener, message: String) {
        UUtils.getHandler().post {
            listener.onError(message)
            listener.onComplete("")
        }
    }

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: JSONArray?
    ): String {
        val arr = JSONArray()
        messages.forEach { msg -> arr.put(messageToJson(msg)) }
        val body = JSONObject()
            .put("model", config.model.trim())
            .put("messages", arr)
            .put("stream", stream)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        return body.toString()
    }

    private fun messageToJson(msg: ChatMessage): JSONObject {
        val obj = JSONObject().put("role", msg.role)
        when (msg.role) {
            ROLE_TOOL -> obj.put("content", msg.content ?: "")
            ROLE_ASSISTANT -> {
                if (msg.content != null) {
                    obj.put("content", msg.content)
                } else {
                    obj.put("content", JSONObject.NULL)
                }
            }
            else -> obj.put("content", msg.content ?: "")
        }
        if (!msg.toolCalls.isNullOrEmpty()) {
            val toolArr = JSONArray()
            msg.toolCalls.forEach { tool ->
                toolArr.put(
                    JSONObject()
                        .put("id", tool.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.name)
                                .put("arguments", tool.arguments)
                        )
                )
            }
            obj.put("tool_calls", toolArr)
        }
        if (!msg.toolCallId.isNullOrBlank()) {
            obj.put("tool_call_id", msg.toolCallId)
        }
        return obj
    }

    private fun parseCompletionResponse(responseBody: String): CompletionResult {
        return try {
            val message = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            val content = if (message.isNull("content")) {
                null
            } else {
                message.optString("content", null)
            }
            val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))
            CompletionResult(content = content, toolCalls = toolCalls, error = null)
        } catch (e: Exception) {
            CompletionResult(
                content = null,
                toolCalls = emptyList(),
                error = "Parse error: ${e.message}"
            )
        }
    }

    private fun parseToolCalls(array: JSONArray?): List<ToolCall> {
        if (array == null || array.length() == 0) return emptyList()
        val list = mutableListOf<ToolCall>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val function = item.getJSONObject("function")
            list.add(
                ToolCall(
                    id = item.getString("id"),
                    name = function.getString("name"),
                    arguments = function.optString("arguments", "{}")
                )
            )
        }
        return list
    }

    private fun parseStreamLine(line: String): String? {
        val clean = line.removePrefix("data: ").trim()
        if (clean.isEmpty()) return null
        if (clean == STREAM_DONE) return STREAM_DONE
        if (clean.first() != '{') return null
        return clean
    }

    private fun extractStreamContent(json: String): String? {
        return try {
            val choices = JSONObject(json).getJSONArray("choices")
            if (choices.length() == 0) return null
            val delta = choices.getJSONObject(0).getJSONObject("delta")
            if (!delta.has("content")) return null
            delta.getString("content")
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ZtAgentAiChatClient"
        private const val STREAM_DONE = "[DONE]"
        private const val ROLE_ASSISTANT = "assistant"
        private const val ROLE_TOOL = "tool"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 429 / 5xx 最多重试次数。超过就交给上层，不再硬撞。 */
        private const val MAX_RETRIES = 5

        /** 流式请求从 UI 线程发起，令牌等待上限，避免界面卡死。 */
        private const val STREAM_ACQUIRE_MS = 800L

        /** 限速器按档案 id 复用，否则每次新建 client 都会重置令牌桶。 */
        private val limiterCache = java.util.concurrent.ConcurrentHashMap<String, ZtRateLimiter>()

        /** 供设置页展示当前端点的限制。 */
        fun profileFor(apiUrl: String): ZtEndpointProfile = ZtEndpointProfiles.fromUrl(apiUrl)
    }
}
