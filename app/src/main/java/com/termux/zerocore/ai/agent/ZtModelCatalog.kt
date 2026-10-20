package com.termux.zerocore.ai.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 模型目录编排层：缓存 + 拉取 + 补全 + 搜索。
 *
 * 缓存文件名是 SHA-256(credential+url)，API Key 不落盘；7 天 TTL；每个端点一个文件，
 * 换 key 不会互相污染。
 */
object ZtModelCatalog {

    private const val TAG = "ZtModelCatalog"
    private const val CACHE_DIR = "zt-models-cache"
    private const val CACHE_VERSION = 1
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zt-model-catalog").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null

    /** 内存兜底：没有 Context 或磁盘不可写时，至少本次进程内不用重复联网。 */
    private val memoryCache = HashMap<String, Pair<Long, List<ZtModelInfo>>>()

    /** 在 Application 或任意 Activity 里调一次即可（内部只持有 applicationContext）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ─── 对外接口 ────────────────────────────────────────────────────────────

    interface ModelsCallback {
        /** 可能是缓存也可能是新鲜数据；[fromCache] 用于 UI 上标注"来自缓存"。 */
        fun onModels(models: List<ZtModelInfo>, fromCache: Boolean)
        fun onError(message: String)
    }

    /**
     * 取模型列表。先回缓存（主线程立即回调一次），再后台刷新；刷新成功再回调一次。
     * [forceRefresh] 为 true 时跳过缓存直接联网。
     */
    fun load(
        apiUrl: String,
        apiKey: String,
        forceRefresh: Boolean = false,
        callback: ModelsCallback
    ) {
        val key = cacheKey(apiUrl, apiKey)
        val cached = if (forceRefresh) null else readCache(key)
        if (!cached.isNullOrEmpty()) {
            post { callback.onModels(cached, true) }
        }
        executor.execute {
            val result = ZtModelsApi.fetchModels(apiUrl, apiKey)
            if (result.isSuccess && result.models.isNotEmpty()) {
                writeCache(key, result.models)
                val fresh = result.models
                post { callback.onModels(fresh, false) }
            } else {
                val error = result.error ?: "No chat models returned"
                if (cached.isNullOrEmpty()) {
                    post { callback.onError(error) }
                } else {
                    // 有缓存就降级用缓存，不打断用户操作，只记日志。
                    android.util.Log.w(TAG, "refresh failed, keeping cache: $error")
                }
            }
        }
    }

    /** 清掉某个端点的缓存，用于"强制刷新"按钮。 */
    fun invalidate(apiUrl: String, apiKey: String) {
        val key = cacheKey(apiUrl, apiKey)
        memoryCache.remove(key)
        runCatching {
            val file = cacheFile(key) ?: return
            file.delete()
        }
    }

    fun clearAll() {
        memoryCache.clear()
        runCatching {
            val dir = cacheDir() ?: return
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /** 本地搜索：ID / 展示名模糊匹配，工具与推理模型优先。 */
    fun search(models: List<ZtModelInfo>, query: String): List<ZtModelInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return sortForDisplay(models)
        return sortForDisplay(
            models.filter {
                it.id.lowercase().contains(q) ||
                    it.displayName.lowercase().contains(q) ||
                    it.family.contains(q)
            }
        )
    }

    /** 展示排序：能用工具的排前面，其次推理，再按家族与 ID。 */
    fun sortForDisplay(models: List<ZtModelInfo>): List<ZtModelInfo> =
        models.sortedWith(
            compareByDescending<ZtModelInfo> { it.supportsTools }
                .thenByDescending { it.supportsReasoning }
                .thenBy { it.family }
                .thenBy { it.id.lowercase() }
        )

    // ─── 缓存实现 ────────────────────────────────────────────────────────────

    /** 缓存键：只做哈希，不落任何明文凭证。 */
    private fun cacheKey(apiUrl: String, apiKey: String): String {
        val material = "${apiUrl.trim()}|${apiKey.trim()}"
        return sha256(material)
    }

    private fun readCache(key: String): List<ZtModelInfo>? {
        memoryCache[key]?.let { (savedAt, models) ->
            if (System.currentTimeMillis() - savedAt <= TTL_MS) return models
            memoryCache.remove(key)
        }
        val file = cacheFile(key) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("version", 0) != CACHE_VERSION) return null
            val savedAt = root.optLong("savedAt", 0L)
            if (System.currentTimeMillis() - savedAt > TTL_MS) return null
            val arr = root.optJSONArray("models") ?: return null
            val list = ArrayList<ZtModelInfo>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(fromJson(arr.getJSONObject(i)))
            }
            list
        }.onFailure {
            android.util.Log.w(TAG, "read cache failed: ${it.message}")
        }.getOrNull()
    }

    private fun writeCache(key: String, models: List<ZtModelInfo>) {
        memoryCache[key] = System.currentTimeMillis() to models
        runCatching {
            val file = cacheFile(key) ?: return
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            models.forEach { arr.put(toJson(it)) }
            val root = JSONObject()
                .put("version", CACHE_VERSION)
                .put("savedAt", System.currentTimeMillis())
                .put("models", arr)
            file.writeText(root.toString())
        }.onFailure {
            android.util.Log.w(TAG, "write cache failed: ${it.message}")
        }
    }

    private fun cacheDir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.cacheDir, CACHE_DIR).apply { mkdirs() }
    }

    private fun cacheFile(key: String): File? {
        val dir = cacheDir() ?: return null
        return File(dir, "$key.json")
    }

    // ─── 序列化（手写，避免引入新依赖） ──────────────────────────────────────

    private fun toJson(model: ZtModelInfo): JSONObject = JSONObject()
        .put("id", model.id)
        .put("displayName", model.displayName)
        .put("contextLength", model.contextLength)
        .put("maxOutput", model.maxOutput)
        .put("tools", model.supportsTools)
        .put("reasoning", model.supportsReasoning)
        .put("vision", model.supportsVision)
        .put("thinking", JSONArray().apply { model.thinkingLevels.forEach { put(it) } })
        .put("source", model.source.name)
        .put("family", model.family)

    private fun fromJson(obj: JSONObject): ZtModelInfo {
        val thinking = JSONArray()
        val arr = obj.optJSONArray("thinking")
        if (arr != null) for (i in 0 until arr.length()) thinking.put(arr.optString(i))
        return ZtModelInfo(
            id = obj.optString("id"),
            displayName = obj.optString("displayName").ifBlank { obj.optString("id") },
            contextLength = obj.optInt("contextLength", ZtModelInfo.UNKNOWN_CONTEXT),
            maxOutput = obj.optInt("maxOutput", ZtModelInfo.UNKNOWN_OUTPUT),
            supportsTools = obj.optBoolean("tools", true),
            supportsReasoning = obj.optBoolean("reasoning", false),
            supportsVision = obj.optBoolean("vision", false),
            thinkingLevels = (0 until thinking.length()).map { thinking.optString(it) }
                .filter { it.isNotBlank() },
            source = runCatching { ZtModelSource.valueOf(obj.optString("source", "STATIC")) }
                .getOrDefault(ZtModelSource.STATIC),
            family = obj.optString("family")
        )
    }

    // ─── 工具 ────────────────────────────────────────────────────────────────

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
