package com.termux.zerocore.ai.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 组内单个模型条目。
 *
 * base 字段来自目录（/v1/models 拉取，或本地推断补全），override 字段是用户改过的；
 * 取有效值时 override 优先，这样目录刷新不会把用户设置冲掉。
 */
data class ZtModelEntry(
    val modelId: String,
    val displayName: String = modelId,
    val provider: String = "",
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsReasoning: Boolean? = null,
    val reasoningEffortValues: List<String>? = null,
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
    val overrideContext: Int? = null,
    val overrideMaxOutput: Int? = null,
    val thinkingLevel: String? = null,
    val isCustom: Boolean = false,
    val isHidden: Boolean = false
) {
    val effectiveContext: Int
        get() = overrideContext ?: contextWindow ?: ZtModelInfo.UNKNOWN_CONTEXT

    val effectiveMaxOutput: Int
        get() = overrideMaxOutput ?: maxOutputTokens ?: ZtModelInfo.UNKNOWN_OUTPUT

    /** 可选档位：目录声明的优先，其次是模型是否支持推理。 */
    val availableThinkingLevels: List<String>
        get() = when {
            !reasoningEffortValues.isNullOrEmpty() -> reasoningEffortValues
            supportsReasoning == true -> listOf("low", "medium", "high")
            else -> emptyList()
        }

    fun toJson(): JSONObject = JSONObject()
        .put("modelId", modelId)
        .put("displayName", displayName)
        .put("provider", provider)
        .put("contextWindow", contextWindow ?: -1)
        .put("maxOutputTokens", maxOutputTokens ?: -1)
        .put("supportsReasoning", supportsReasoning ?: false)
        .put("reasoningEffortValues", JSONArray().apply {
            (reasoningEffortValues ?: emptyList<String>()).forEach { put(it) }
        })
        .put("supportsVision", supportsVision ?: false)
        .put("supportsTools", supportsTools ?: false)
        .put("overrideContext", overrideContext ?: -1)
        .put("overrideMaxOutput", overrideMaxOutput ?: -1)
        .put("thinkingLevel", thinkingLevel ?: "")
        .put("isCustom", isCustom)
        .put("isHidden", isHidden)

    companion object {
        fun fromJson(obj: JSONObject): ZtModelEntry = ZtModelEntry(
            modelId = obj.optString("modelId"),
            displayName = obj.optString("displayName").ifBlank { obj.optString("modelId") },
            provider = obj.optString("provider"),
            contextWindow = obj.optInt("contextWindow", -1).takeIf { it > 0 },
            maxOutputTokens = obj.optInt("maxOutputTokens", -1).takeIf { it > 0 },
            supportsReasoning = obj.optBoolean("supportsReasoning", false),
            reasoningEffortValues = jsonToStringList(obj.optJSONArray("reasoningEffortValues")),
            supportsVision = obj.optBoolean("supportsVision", false),
            supportsTools = obj.optBoolean("supportsTools", false),
            overrideContext = obj.optInt("overrideContext", -1).takeIf { it > 0 },
            overrideMaxOutput = obj.optInt("overrideMaxOutput", -1).takeIf { it > 0 },
            thinkingLevel = obj.optString("thinkingLevel").ifBlank { null },
            isCustom = obj.optBoolean("isCustom", false),
            isHidden = obj.optBoolean("isHidden", false)
        )

        /** 目录里的模型转成条目；目录刷新时用这个重建，保留已有 override。 */
        fun fromModelInfo(info: ZtModelInfo, previous: ZtModelEntry? = null): ZtModelEntry =
            ZtModelEntry(
                modelId = info.id,
                displayName = info.displayName,
                provider = info.family,
                contextWindow = info.contextLength,
                maxOutputTokens = info.maxOutput,
                supportsReasoning = info.supportsReasoning,
                reasoningEffortValues = info.thinkingLevels.takeIf { it.isNotEmpty() },
                supportsVision = info.supportsVision,
                supportsTools = info.supportsTools,
                overrideContext = previous?.overrideContext,
                overrideMaxOutput = previous?.overrideMaxOutput,
                thinkingLevel = previous?.thinkingLevel,
                isCustom = previous?.isCustom ?: (info.source == ZtModelSource.STATIC)
            )

        private fun jsonToStringList(arr: JSONArray?): List<String>? {
            if (arr == null || arr.length() == 0) return null
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotBlank()) out.add(v)
            }
            return out
        }
    }
}

/**
 * 模型组：有序的一组模型，带路由策略与共享限制。
 *
 * memberIds 的顺序是 fallback 顺序，删改时不能重排。
 */
data class ZtModelGroup(
    val id: String,
    val name: String,
    /** ROUTE_SINGLE 只用首选；ROUTE_FALLBACK 失败时按顺序切下一个。 */
    val strategy: String = ROUTE_FALLBACK,
    val defaultThinkingLevel: String? = null,
    val contextLimitTokens: Int? = null,
    val memberIds: List<String> = emptyList(),
    val sortOrder: Int = 0
) {
    val isFallback: Boolean get() = strategy == ROUTE_FALLBACK

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("strategy", strategy)
        .put("defaultThinkingLevel", defaultThinkingLevel ?: "")
        .put("contextLimitTokens", contextLimitTokens ?: -1)
        .put("memberIds", JSONArray().apply { memberIds.forEach { put(it) } })
        .put("sortOrder", sortOrder)

    companion object {
        const val ROUTE_SINGLE = "single"
        const val ROUTE_FALLBACK = "fallback"

        fun fromJson(obj: JSONObject): ZtModelGroup {
            val ids = ArrayList<String>()
            obj.optJSONArray("memberIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (v.isNotBlank()) ids.add(v)
                }
            }
            return ZtModelGroup(
                id = obj.optString("id"),
                name = obj.optString("name"),
                strategy = obj.optString("strategy", ROUTE_FALLBACK),
                defaultThinkingLevel = obj.optString("defaultThinkingLevel").ifBlank { null },
                contextLimitTokens = obj.optInt("contextLimitTokens", -1).takeIf { it > 0 },
                memberIds = ids,
                sortOrder = obj.optInt("sortOrder", 0)
            )
        }
    }
}
