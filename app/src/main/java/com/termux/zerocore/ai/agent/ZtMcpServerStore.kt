package com.termux.zerocore.ai.agent

import com.example.xh_lib.utils.SaveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * MCP 服务器配置存储。支持 public（无凭据）与 bearer（静态 Authorization / 自定义头）两种。
 *
 * 存通用 KV（SaveData），不改动 ZTUserBean 结构。
 */
object ZtMcpServerStore {

    private const val KEY_SERVERS = "zt_mcp_servers_json"
    private const val KEY_ENABLED = "zt_mcp_enabled"

    private val gson = Gson()

    enum class AuthType { NONE, BEARER, HEADERS }

    data class McpServer(
        var id: String = "",
        var name: String = "",
        var url: String = "",
        var enabled: Boolean = true,
        /** 自定义请求头；BEARER 时自动补一条 Authorization。 */
        var headers: LinkedHashMap<String, String> = LinkedHashMap(),
        var authType: AuthType = AuthType.NONE,
        /** BEARER 模式的 token，单独存放以便在 UI 上打码显示。 */
        var bearerToken: String = "",
        /** 该服务器暴露的工具数量，最近一次发现后回填，供 UI 展示。 */
        var lastToolCount: Int = 0,
        var lastError: String = ""
    ) {
        fun effectiveHeaders(): Map<String, String> {
            val out = LinkedHashMap(headers)
            if (authType == AuthType.BEARER && bearerToken.isNotBlank()) {
                out["Authorization"] = "Bearer ${bearerToken.trim()}"
            }
            return out
        }
    }

    // ─── 全局开关 ────────────────────────────────────────────────────────────

    fun isEnabled(): Boolean = readString(KEY_ENABLED)?.let { it == "1" } ?: false

    fun saveEnabled(enabled: Boolean) {
        SaveData.saveStringOther(KEY_ENABLED, if (enabled) "1" else "0")
    }

    // ─── 增删改查 ────────────────────────────────────────────────────────────

    fun list(): MutableList<McpServer> {
        val json = readString(KEY_SERVERS) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<McpServer>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun enabledServers(): List<McpServer> =
        list().filter { it.enabled && it.url.isNotBlank() }

    fun save(servers: List<McpServer>) {
        // 存储前把 id 补齐，避免外部传入空 id 导致无法定位。
        servers.forEach { if (it.id.isBlank()) it.id = newId(it.name) }
        SaveData.saveStringOther(KEY_SERVERS, gson.toJson(servers))
    }

    fun upsert(server: McpServer) {
        val all = list()
        if (server.id.isBlank()) server.id = newId(server.name)
        val index = all.indexOfFirst { it.id == server.id }
        if (index >= 0) all[index] = server else all.add(server)
        save(all)
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
    }

    fun find(id: String): McpServer? = list().firstOrNull { it.id == id }

    /** 回填最近一次工具发现的结果，供 UI 展示"N 个工具 / 最近错误"。 */
    fun updateStatus(id: String, toolCount: Int, error: String?) {
        val all = list()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return
        all[index].lastToolCount = toolCount
        all[index].lastError = error.orEmpty()
        save(all)
    }

    // ─── 名称规范化 ──────────────────────────────────────────────────────────

    private fun newId(name: String): String {
        val base = name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "mcp" }
        val existing = list().map { it.id }.toSet()
        if (base !in existing) return base
        var n = 2
        while ("$base-$n" in existing) n++
        return "$base-$n"
    }

    /**
     * 工具名前缀用的服务器别名：只保留 [a-z0-9_]，因为 OpenAI 的
     * function name 只允许 `[a-zA-Z0-9_-]`。
     */
    fun aliasOf(server: McpServer): String {
        val raw = server.name.ifBlank { server.id }.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return raw.ifBlank { "mcp" }
    }

    private fun readString(key: String): String? {
        val raw = SaveData.getStringOther(key)
        if (raw.isNullOrBlank() || raw == "def") return null
        return raw
    }
}
