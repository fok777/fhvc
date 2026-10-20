package com.termux.zerocore.ai.agent


data class ZtModelInfo(
    val id: String,
    val displayName: String = id,
    /** 上下文窗口（token）；未知时为 UNKNOWN_CONTEXT。 */
    val contextLength: Int = 128_000,
    /** 单次最大输出（token）；未知时为 UNKNOWN_OUTPUT。 */
    val maxOutput: Int = 8_192,
    /** 是否支持 function calling / tool use。 */
    val supportsTools: Boolean = false,
    /** 是否是推理模型（会产出 <think> 或 reasoning_content）。 */
    val supportsReasoning: Boolean = false,
    /** 是否接受图片输入。 */
    val supportsVision: Boolean = false,
    /** 可选的思考强度档位，空列表表示不支持调节。 */
    val thinkingLevels: List<String> = emptyList(),
    /** 这条记录是内置推断出来的，还是从服务端 /v1/models 拉到的。 */
    val source: ZtModelSource = ZtModelSource.STATIC,
    /** 模型家族，用于分组展示与排序。 */
    val family: String = ""
) {
    /** 是否支持调节思考强度。 */
    val hasThinkingControl: Boolean get() = thinkingLevels.isNotEmpty()

    /** 展示用的能力标签，已排序保证 UI 稳定。 */
    val badges: List<String>
        get() = buildList {
            if (supportsTools) add("tools")
            if (supportsReasoning) add("reasoning")
            if (supportsVision) add("vision")
        }

    override fun toString(): String =
        "ZtModelInfo($id, ctx=$contextLength, out=$maxOutput, ${badges.joinToString(",")})"

    companion object {
        const val UNKNOWN_CONTEXT = 128_000
        const val UNKNOWN_OUTPUT = 8_192

        /** 兜底：完全不认识的模型 ID 也能进列表，按纯文本模型对待。 */
        fun unknown(id: String): ZtModelInfo = ZtModelInfo(
            id = id,
            displayName = id,
            source = ZtModelSource.UNKNOWN
        )
    }
}

enum class ZtModelSource {
    /** 由本地规则表推断。 */
    STATIC,

    /** 从服务端 /v1/models 拉取得到。 */
    REMOTE,

    /** 远端拉到了，但本地规则表也认识，能力已补全。 */
    REMOTE_ENRICHED,

    /** 完全不认识的模型 ID。 */
    UNKNOWN
}

/** 思考强度档位常量。不同厂商档位名差异很大，这里取交集最大的三个。 */
object ZtThinkingLevel {
    const val MINIMAL = "minimal"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"

    /** OpenAI / GPT-5 系：low / medium / high。 */
    val OPENAI_STYLE = listOf(LOW, MEDIUM, HIGH)

    /** Gemini 2.5 系：外加 minimal。 */
    val GEMINI_STYLE = listOf(MINIMAL, LOW, MEDIUM, HIGH)

    /** Claude extended thinking：只有一档开关，用 low/high 表达 token 预算。 */
    val CLAUDE_STYLE = listOf(LOW, HIGH)
}
