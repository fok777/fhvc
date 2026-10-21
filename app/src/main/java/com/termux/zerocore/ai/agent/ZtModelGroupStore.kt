package com.termux.zerocore.ai.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型组的持久化与路由。
 *
 * 存 JSON 而不是建表：组数量是个位数，量级不需要数据库，
 * 也避免为了几个条目引入 Room 迁移。
 */
object ZtModelGroupStore {

    private const val KEY_GROUPS = "zt_model_groups_json"
    private const val KEY_ENTRIES = "zt_model_entries_json"
    private const val KEY_ACTIVE = "zt_model_active_group"
    private const val KEY_LAST_GOOD = "zt_model_last_good"

    fun loadGroups(): List<ZtModelGroup> {
        val raw = com.example.xh_lib.utils.SaveData.getStringOther(KEY_GROUPS)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<ZtModelGroup>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(ZtModelGroup.fromJson(arr.getJSONObject(i)))
            }
            out.sortedBy { it.sortOrder }
        }.getOrDefault(emptyList())
    }

    fun saveGroups(groups: List<ZtModelGroup>) {
        val arr = JSONArray()
        groups.forEachIndexed { index, group ->
            arr.put(group.copy(sortOrder = index).toJson())
        }
        com.example.xh_lib.utils.SaveData.saveStringOther(KEY_GROUPS, arr.toString())
    }

    fun loadEntries(): Map<String, ZtModelEntry> {
        val raw = com.example.xh_lib.utils.SaveData.getStringOther(KEY_ENTRIES)
            ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = linkedMapOf<String, ZtModelEntry>()
            obj.keys().forEach { key ->
                out[key] = ZtModelEntry.fromJson(obj.getJSONObject(key))
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun saveEntries(entries: Map<String, ZtModelEntry>) {
        val obj = JSONObject()
        entries.forEach { (key, entry) -> obj.put(key, entry.toJson()) }
        com.example.xh_lib.utils.SaveData.saveStringOther(KEY_ENTRIES, obj.toString())
    }

    fun activeGroupId(): String =
        com.example.xh_lib.utils.SaveData.getStringOther(KEY_ACTIVE).orEmpty()

    fun saveActiveGroup(id: String) {
        com.example.xh_lib.utils.SaveData.saveStringOther(KEY_ACTIVE, id)
    }

    /**
     * 上一个请求成功的成员。
     * 下次优先试它，避免每次都从队首那个已经打满的开始撞。
     */
    fun lastGoodModelId(): String? {
        val group = activeGroup() ?: return null
        val raw = com.example.xh_lib.utils.SaveData.getStringOther(KEY_LAST_GOOD).orEmpty()
        val ids = group.memberIds
        return raw.takeIf { it in ids }
    }

    fun markGood(modelId: String) {
        com.example.xh_lib.utils.SaveData.saveStringOther(KEY_LAST_GOOD, modelId)
    }

    fun findGroup(id: String): ZtModelGroup? =
        loadGroups().firstOrNull { it.id == id }

    fun activeGroup(): ZtModelGroup? {
        val groups = loadGroups()
        val active = activeGroupId()
        return groups.firstOrNull { it.id == active } ?: groups.firstOrNull()
    }

    /**
     * 组内成员，按 fallback 顺序。
     * 成员缺失（条目被删）时跳过，不留空洞。
     */
    fun membersOf(group: ZtModelGroup): List<ZtModelEntry> {
        val entries = loadEntries()
        return group.memberIds.mapNotNull { entries[it] }
    }

    /** 组里第一个可用成员；没有就用配置里的裸模型 ID。 */
    fun resolveModelId(fallbackRaw: String): String {
        val group = activeGroup() ?: return fallbackRaw
        return membersOf(group).firstOrNull { !it.isHidden }?.modelId
            ?: membersOf(group).firstOrNull()?.modelId
            ?: fallbackRaw
    }

    /** 当前组用于请求的有效上下文，用于上下文预算。 */
    fun effectiveContext(fallback: Int = ZtModelInfo.UNKNOWN_CONTEXT): Int {
        val group = activeGroup() ?: return fallback
        val member = membersOf(group).firstOrNull() ?: return fallback
        val limit = group.contextLimitTokens
        val ctx = member.effectiveContext
        return if (limit != null && limit > 0) minOf(limit, ctx) else ctx
    }

    fun upsertEntry(entry: ZtModelEntry) {
        val entries = loadEntries().toMutableMap()
        entries[entry.modelId] = entry
        saveEntries(entries)
    }

    fun removeEntry(modelId: String) {
        val entries = loadEntries().toMutableMap()
        entries.remove(modelId)
        saveEntries(entries)
        // 同步摘掉各组里的引用，否则会留下空槽
        val groups = loadGroups().map { group ->
            if (group.memberIds.contains(modelId)) {
                group.copy(memberIds = group.memberIds.filterNot { it == modelId })
            } else {
                group
            }
        }
        saveGroups(groups)
    }

    fun upsertGroup(group: ZtModelGroup) {
        val groups = loadGroups().toMutableList()
        val index = groups.indexOfFirst { it.id == group.id }
        if (index >= 0) groups[index] = group else groups.add(group)
        saveGroups(groups)
    }

    fun removeGroup(id: String) {
        saveGroups(loadGroups().filterNot { it.id == id })
        if (activeGroupId() == id) saveActiveGroup("")
    }

    /**
     * 目录刷新后重建条目：沿用已有 override，避免把用户改过的上下文/档位冲掉。
     */
    fun syncFromCatalog(models: List<ZtModelInfo>) {
        val entries = loadEntries().toMutableMap()
        models.forEach { info ->
            entries[info.id] = ZtModelEntry.fromModelInfo(info, entries[info.id])
        }
        saveEntries(entries)
    }

    fun clearAll() {
        saveGroups(emptyList())
        saveEntries(emptyMap())
        saveActiveGroup("")
        com.example.xh_lib.utils.SaveData.saveStringOther(KEY_LAST_GOOD, "")
    }
}
