package com.termux.zerocore.ai.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 从服务端拉取可用模型列表（`/v1/models` 及其变体）。
 *
 * 兼容四种返回体：OpenAI / 多数中转站的 `data[].id`、少数中转站的 `data[]` 字符串数组、
 * Gemini 原生的 `models[]`、Anthropic 的 `data[]`。拉到的裸 ID 再交给 ZtModelRegistry
 * 补全能力，整条链路不联网二次查询。
 *
 * 同步方法，必须在子线程调用。
 */
object ZtModelsApi {

    private const val TAG = "ZtModelsApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 非聊天模型的关键词，拉到列表后要过滤掉，否则选择器里会混进一堆 TTS / 向量模型。 */
    private val NON_CHAT_KEYWORDS = listOf(
        "embedding", "embed", "text-embedding", "babbage", "davinci", "curie", "ada",
        "tts", "whisper", "transcribe", "speech", "audio", "voice",
        "dall-e", "dalle", "image", "imagen", "flux", "stable-diffusion", "sd-",
        "rerank", "reranker", "moderation", "moderate",
        "video", "sora", "kling", "detection", "ocr"
    )

    data class FetchResult(
        val models: List<ZtModelInfo>,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null
    }

    /**
     * 拉取并补全模型列表。
     * @param apiUrl 设置里填的完整 chat completions 地址，自动推导 models 端点
     * @param apiKey Bearer key
     */
    fun fetchModels(apiUrl: String, apiKey: String): FetchResult {
        val endpoint = modelsEndpoint(apiUrl)
        if (endpoint.isBlank()) {
            return FetchResult(emptyList(), "Cannot derive a /models endpoint from the API URL")
        }
        if (apiKey.isBlank()) {
            return FetchResult(emptyList(), "API key is empty")
        }
        return try {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "fetch failed HTTP ${response.code}")
                    return FetchResult(emptyList(), "HTTP ${response.code}: ${body.take(200)}")
                }
                val ids = parseModelIds(body)
                android.util.Log.i(TAG, "fetched ${ids.size} raw models from $endpoint")
                val models = ids
                    .filter { isChatModel(it) }
                    .map { ZtModelRegistry.resolve(it, ZtModelSource.REMOTE_ENRICHED) }
                    .sortedWith(compareByDescending<ZtModelInfo> { it.supportsTools }
                        .thenByDescending { it.supportsReasoning }
                        .thenBy { it.id.lowercase() })
                FetchResult(models)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "fetch error: ${e.message}")
            FetchResult(emptyList(), e.message ?: "Request failed")
        }
    }

    /**
     * 从 chat completions 地址推导 models 端点。
     *   `https://api.openai.com/v1/chat/completions` → `https://api.openai.com/v1/models`
     *   `https://api.deepseek.com/chat/completions`  → `https://api.deepseek.com/models`
     *   `...v1beta/openai/chat/completions`          → `...v1beta/openai/models`
     */
    fun modelsEndpoint(apiUrl: String): String {
        val raw = apiUrl.trim().trimEnd('/')
        if (raw.isBlank()) return ""
        val lower = raw.lowercase()
        val base = when {
            lower.endsWith("/chat/completions") ->
                raw.substring(0, raw.length - "/chat/completions".length)
            lower.endsWith("/completions") ->
                raw.substring(0, raw.length - "/completions".length)
            lower.endsWith("/responses") ->
                raw.substring(0, raw.length - "/responses".length)
            lower.endsWith("/models") -> return raw
            else -> raw
        }.trimEnd('/')
        return "$base/models"
    }

    /** 解析四种返回体，统一输出模型 ID 列表（已去重、保持顺序）。 */
    internal fun parseModelIds(body: String): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            val root = JSONObject(body)

            // 1 & 2 & 4：OpenAI / Anthropic 风格 data 数组
            val data = root.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    when (val item = data.get(i)) {
                        is JSONObject -> {
                            val id = item.optString("id").ifBlank { item.optString("name") }
                            if (id.isNotBlank()) out.add(normalizeId(id))
                        }
                        is String -> if (item.isNotBlank()) out.add(normalizeId(item))
                    }
                }
            }

            // 3：Gemini 原生 models 数组
            val models = root.optJSONArray("models")
            if (models != null) {
                for (i in 0 until models.length()) {
                    val item = models.optJSONObject(i) ?: continue
                    val name = item.optString("name").ifBlank { item.optString("id") }
                    if (name.isNotBlank()) out.add(normalizeId(name))
                }
            }

            // 兜底：顶层就是字符串数组
            if (out.isEmpty() && root.optJSONArray("object") == null) {
                val direct = runCatching { JSONArray(body) }.getOrNull()
                if (direct != null) {
                    for (i in 0 until direct.length()) {
                        val v = direct.optString(i)
                        if (!v.isNullOrBlank()) out.add(normalizeId(v))
                    }
                }
            }
        }.onFailure {
            android.util.Log.w(TAG, "parse model ids failed: ${it.message}")
        }
        return out.toList()
    }

    /** Gemini 返回 `models/gemini-2.5-flash`，去掉前缀。 */
    private fun normalizeId(raw: String): String {
        val id = raw.trim()
        return if (id.startsWith("models/", ignoreCase = true)) id.substring("models/".length) else id
    }

    /** 过滤掉非聊天模型。命中关键词但不是"同时带 chat 字样"的，一律剔除。 */
    internal fun isChatModel(id: String): Boolean {
        val lower = id.lowercase()
        val hit = NON_CHAT_KEYWORDS.firstOrNull { lower.contains(it) }
        if (hit == null) return true
        // 少数模型名里带 image 但确实是聊天模型（如 gpt-4o-image），保留。
        return lower.contains("chat") || lower.contains("instruct") || lower.contains("vision")
    }
}
