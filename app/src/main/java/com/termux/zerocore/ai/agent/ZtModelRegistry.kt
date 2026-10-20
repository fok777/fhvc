package com.termux.zerocore.ai.agent

/**
 * 模型能力推断：把模型 ID 分词后用规则匹配，推断 vision / tools / reasoning、
 * 上下文长度、最大输出与思考档位。
 *
 * 不维护全量清单，因为用户接的多半是中转站 / 自建 vLLM / Ollama，模型 ID 千奇百怪，
 * 写死清单必然漏；规则表离线可用，加新模型只需加一行 defineModel。
 */
object ZtModelRegistry {

    private const val TAG = "ZtModelRegistry"

    // ─── OpenAI ─────────────────────────────────────────────────────────────

    private val GPT_4O = defineModel {
        tokens("gpt", "4", "o")
        vision()
        tool()
        context(128_000)
        output(16_384)
    }

    private val GPT_41 = defineModel {
        tokens("gpt", "4", "1")
        vision()
        tool()
        context(1_000_000)
        output(32_768)
    }

    private val GPT_5 = defineModel {
        tokens("gpt", "5")
        notTokens("gpt", "5", "chat")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.OPENAI_STYLE)
        context(400_000)
        output(128_000)
    }

    private val GPT_51 = defineModel {
        tokens("gpt", "5", "1")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.OPENAI_STYLE)
        context(400_000)
        output(128_000)
    }

    private val GPT_52 = defineModel {
        tokens("gpt", "5", "2")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.OPENAI_STYLE)
        context(400_000)
        output(128_000)
    }

    private val GPT_53 = defineModel {
        tokens("gpt", "5", "3")
        vision()
        tool()
        context(400_000)
        output(128_000)
    }

    private val GPT_54 = defineModel {
        tokens("gpt", "5", "4")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.OPENAI_STYLE)
        context(400_000)
        output(128_000)
    }

    private val GPT_55 = defineModel {
        tokens("gpt", "5", "5")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.OPENAI_STYLE)
        context(400_000)
        output(128_000)
    }

    private val GPT_56 = defineModel {
        tokens("gpt", "5", "6")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.OPENAI_STYLE)
        context(400_000)
        output(128_000)
    }

    /** `gpt-5-chat-*` 是 GPT-5 的非推理分支，必须显式声明，否则会被 GPT_5 的 notTokens 排除成未知模型。 */
    private val GPT_5_CHAT = defineModel {
        tokens("gpt", "5", "chat")
        vision()
        tool()
        context(400_000)
        output(128_000)
    }

    /** o1 / o3 / o4-mini 这类"字母+纯数字"的推理系列。 */
    private val O_SERIES = defineModel {
        tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.OPENAI_STYLE)
        context(200_000)
        output(100_000)
    }

    private val CODEX = defineModel {
        tokens("codex")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.OPENAI_STYLE)
        context(400_000)
        output(128_000)
    }

    // ─── Anthropic ──────────────────────────────────────────────────────────

    private val CLAUDE_OPUS_4 = defineModel {
        tokens("claude", "opus", "4")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.CLAUDE_STYLE)
        context(200_000)
        output(64_000)
    }

    private val CLAUDE_SONNET_4 = defineModel {
        tokens("claude", "sonnet", "4")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.CLAUDE_STYLE)
        context(200_000)
        output(64_000)
    }

    private val CLAUDE_HAIKU = defineModel {
        tokens("claude", "haiku")
        vision()
        tool()
        context(200_000)
        output(64_000)
    }

    private val CLAUDE_37 = defineModel {
        tokens("claude", "3", "7")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.CLAUDE_STYLE)
        context(200_000)
        output(64_000)
    }

    private val CLAUDE_GENERIC = defineModel {
        tokens("claude")
        vision()
        tool()
        context(200_000)
        output(32_000)
    }

    // ─── Google ─────────────────────────────────────────────────────────────

    private val GEMINI_20 = defineModel {
        tokens("gemini", "2", "0")
        vision()
        tool()
        context(1_000_000)
        output(8_192)
    }

    private val GEMINI_25 = defineModel {
        tokens("gemini", "2", "5")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.GEMINI_STYLE)
        context(1_000_000)
        output(65_536)
    }

    private val GEMINI_3 = defineModel {
        tokens("gemini", "3")
        vision()
        toolReasoning()
        thinking(ZtThinkingLevel.GEMINI_STYLE)
        context(1_000_000)
        output(65_536)
    }

    private val GEMINI_GENERIC = defineModel {
        tokens("gemini")
        vision()
        tool()
        context(1_000_000)
        output(8_192)
    }

    // ─── DeepSeek ───────────────────────────────────────────────────────────

    private val DEEPSEEK_R1 = defineModel {
        tokens("deepseek", "r", "1")
        toolReasoning()
        context(128_000)
        output(64_000)
    }

    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        toolReasoning()
        context(128_000)
        output(64_000)
    }

    private val DEEPSEEK_V3 = defineModel {
        tokens("deepseek", "v", "3")
        tool()
        context(128_000)
        output(8_192)
    }

    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        tool()
        context(128_000)
        output(8_192)
    }

    private val DEEPSEEK_GENERIC = defineModel {
        tokens("deepseek")
        tool()
        context(128_000)
        output(8_192)
    }

    // ─── 阿里 Qwen ──────────────────────────────────────────────────────────

    private val QWQ = defineModel {
        tokens("qwq")
        toolReasoning()
        context(128_000)
        output(32_000)
    }

    private val QWEN3 = defineModel {
        tokens("qwen", "3")
        toolReasoning()
        context(128_000)
        output(32_000)
    }

    private val QWEN25 = defineModel {
        tokens("qwen", "2", "5")
        tool()
        context(128_000)
        output(8_192)
    }

    private val QWEN_GENERIC = defineModel {
        tokens("qwen")
        tool()
        context(128_000)
        output(8_192)
    }

    // ─── 智谱 GLM / Kimi / MiniMax / Grok / 其它 ─────────────────────────────

    private val GLM = defineModel {
        tokens("glm")
        toolReasoning()
        context(128_000)
        output(32_000)
    }

    private val KIMI_K2 = defineModel {
        tokens("kimi", "k", "2")
        toolReasoning()
        context(128_000)
        output(32_000)
    }

    private val KIMI_GENERIC = defineModel {
        tokens("kimi")
        tool()
        context(128_000)
        output(32_000)
    }

    private val MOONSHOT = defineModel {
        tokens("moonshot")
        tool()
        context(128_000)
        output(8_192)
    }

    private val MINIMAX = defineModel {
        tokens("minimax")
        toolReasoning()
        context(200_000)
        output(32_000)
    }

    private val GROK = defineModel {
        tokens("grok")
        vision()
        toolReasoning()
        context(256_000)
        output(32_000)
    }

    private val LLAMA = defineModel {
        tokens("llama")
        tool()
        context(128_000)
        output(8_192)
    }

    private val MISTRAL = defineModel {
        tokens("mistral")
        tool()
        context(128_000)
        output(8_192)
    }

    private val DOUBAO = defineModel {
        tokens("doubao")
        vision()
        tool()
        context(256_000)
        output(16_384)
    }

    private val HUNYUAN = defineModel {
        tokens("hunyuan")
        tool()
        context(128_000)
        output(16_384)
    }

    private val ERNIE = defineModel {
        tokens("ernie")
        tool()
        context(128_000)
        output(16_384)
    }

    private val STEP = defineModel {
        tokens("step")
        vision()
        tool()
        context(256_000)
        output(32_000)
    }

    private val YI = defineModel {
        tokens("yi")
        tool()
        context(128_000)
        output(8_192)
    }

    private val GAMMA_OSS = defineModel {
        tokens("gpt", "oss")
        toolReasoning()
        context(128_000)
        output(32_000)
    }

    // ─── 推理模型的兜底启发式 ────────────────────────────────────────────────

    /**
     * 上面规则都没命中、但 ID 里带明显推理关键词的情况。
     * 国产中转站尤其常见：`xxx-r1`、`xxx-thinking`、`xxx-reasoner`。
     */
    private val REASONING_HINTS = listOf(
        "reasoner", "reasoning", "thinking", "think", "r1", "o1", "o3", "o4"
    )

    private val VISION_HINTS = listOf("vision", "vl", "multimodal", "omni")

    private val ALL_MODELS: List<ZtModelDefinition> = listOf(
        GPT_4O, GPT_41, GPT_5, GPT_51, GPT_52, GPT_53, GPT_54, GPT_55, GPT_56, GPT_5_CHAT,
        O_SERIES, CODEX, GAMMA_OSS,
        CLAUDE_OPUS_4, CLAUDE_SONNET_4, CLAUDE_HAIKU, CLAUDE_37, CLAUDE_GENERIC,
        GEMINI_20, GEMINI_25, GEMINI_3, GEMINI_GENERIC,
        DEEPSEEK_R1, DEEPSEEK_REASONER, DEEPSEEK_V3, DEEPSEEK_CHAT, DEEPSEEK_GENERIC,
        QWQ, QWEN3, QWEN25, QWEN_GENERIC,
        GLM, KIMI_K2, KIMI_GENERIC, MOONSHOT, MINIMAX, GROK,
        LLAMA, MISTRAL, DOUBAO, HUNYUAN, ERNIE, STEP, YI
    )

    /**
     * 把模型 ID 解析成能力描述。
     * @param id 模型 ID，如 `gpt-5.4`、`deepseek-reasoner`、`Qwen/Qwen3-235B`
     * @param source 来源标记，远端拉取到时用 REMOTE_ENRICHED
     */
    fun resolve(id: String, source: ZtModelSource = ZtModelSource.STATIC): ZtModelInfo {
        val raw = id.trim()
        if (raw.isEmpty()) return ZtModelInfo.unknown(raw)

        val matches = resolveDefinitions(raw)
        val lower = raw.lowercase()

        val tools = matches.any { it.tools }
        val reasoning = matches.any { it.reasoning } ||
            REASONING_HINTS.any { hint -> containsToken(lower, hint) }
        val vision = matches.any { it.vision } ||
            VISION_HINTS.any { hint -> containsToken(lower, hint) }

        val context = matches.firstNotNullOfOrNull { it.contextLength }
            ?: ZtModelInfo.UNKNOWN_CONTEXT
        val output = matches.firstNotNullOfOrNull { it.maxOutput }
            ?: ZtModelInfo.UNKNOWN_OUTPUT
        val thinking = matches.firstOrNull { it.thinkingLevels.isNotEmpty() }?.thinkingLevels
            .orEmpty()

        return ZtModelInfo(
            id = raw,
            displayName = prettify(raw),
            contextLength = context,
            maxOutput = output,
            supportsTools = if (matches.isEmpty()) true else tools,
            supportsReasoning = reasoning,
            supportsVision = vision,
            thinkingLevels = thinking,
            source = if (matches.isEmpty()) ZtModelSource.UNKNOWN else source,
            family = familyOf(raw)
        )
    }

    /** 只判断"是否已知"，用于决定要不要展示"未知模型"提示。 */
    fun isKnown(id: String): Boolean = resolveDefinitions(id.trim()).isNotEmpty()

    // ─── 匹配核心 ────────────────────────────────────────────────────────────

    private fun resolveDefinitions(modelId: String): List<ZtModelDefinition> {
        var bestScore: Int? = null
        val matches = mutableListOf<ZtModelDefinition>()
        for (definition in ALL_MODELS) {
            val score = definition.matchScore(modelId) ?: continue
            when {
                bestScore == null || score > bestScore -> {
                    bestScore = score
                    matches.clear()
                    matches.add(definition)
                }
                score == bestScore -> matches.add(definition)
            }
        }
        return matches
    }

    /** 分词：字母串、数字串各自成 token，其它单字符成 token。`gpt-4o` → [gpt, -, 4, o]。 */
    private fun tokenize(modelId: String): List<String> {
        val tokens = mutableListOf<String>()
        val input = modelId.lowercase()
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            when {
                ch.isLetter() -> {
                    val start = index
                    index++
                    while (index < input.length && input[index].isLetter()) index++
                    tokens.add(input.substring(start, index))
                }
                ch.isDigit() -> {
                    val start = index
                    index++
                    while (index < input.length && input[index].isDigit()) index++
                    tokens.add(input.substring(start, index))
                }
                else -> {
                    tokens.add(ch.toString())
                    index++
                }
            }
        }
        return tokens
    }

    /** 判断小写 ID 里是否含某个完整词（避免 `r1` 命中 `Brainstorm` 这类误判）。 */
    private fun containsToken(lowerId: String, hint: String): Boolean {
        val idx = lowerId.indexOf(hint)
        if (idx < 0) return false
        val before = idx == 0 || !lowerId[idx - 1].isLetterOrDigit()
        val after = idx + hint.length >= lowerId.length ||
            !lowerId[idx + hint.length].isLetterOrDigit()
        return before && after
    }

    /** 展示名：把 `Qwen/Qwen3-235B-A22B` 这类 ID 变得可读一点。 */
    private fun prettify(id: String): String {
        val tail = id.substringAfterLast('/')
        return tail.replace('-', ' ').replace('_', ' ').trim()
            .split(' ')
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            .ifBlank { id }
    }

    /** 分组用的家族名，取 ID 首个字母段。 */
    private fun familyOf(id: String): String {
        val head = id.substringAfterLast('/').substringBefore('-').lowercase()
        return head.replace(Regex("[^a-z0-9]"), "").ifBlank { "other" }
    }

    // ─── DSL 实现 ───────────────────────────────────────────────────────────

    internal class ZtModelDefinition(
        private val matcher: ZtTokenMatcher,
        val vision: Boolean,
        val tools: Boolean,
        val reasoning: Boolean,
        val contextLength: Int?,
        val maxOutput: Int?,
        val thinkingLevels: List<String>
    ) {
        fun matchScore(modelId: String): Int? {
            val tokens = tokenize(modelId)
            return matcher.score(modelId, tokens)
        }
    }

    private class ZtDefinitionBuilder {
        private val matchers = mutableListOf<ZtTokenMatcher>()
        private var vision = false
        private var tools = false
        private var reasoning = false
        private var contextLength: Int? = null
        private var maxOutput: Int? = null
        private var thinkingLevels: List<String> = emptyList()

        fun tokens(vararg specs: Any) {
            matchers += ZtSequenceMatcher(specs.map(::toSpec))
        }

        fun notTokens(vararg specs: Any) {
            matchers += ZtNotMatcher(ZtSequenceMatcher(specs.map(::toSpec)))
        }

        fun exact(id: String) {
            matchers += ZtExactMatcher(id)
        }

        fun vision() { vision = true }

        fun tool() { tools = true }

        fun toolReasoning() { tools = true; reasoning = true }

        fun thinking(levels: List<String>) { thinkingLevels = levels }

        fun context(tokens: Int) { contextLength = tokens }

        fun output(tokens: Int) { maxOutput = tokens }

        fun build(): ZtModelDefinition = ZtModelDefinition(
            matcher = when {
                matchers.isEmpty() -> ZtMatchNone
                matchers.size == 1 -> matchers.first()
                else -> ZtAndMatcher(matchers.toList())
            },
            vision = vision,
            tools = tools,
            reasoning = reasoning,
            contextLength = contextLength,
            maxOutput = maxOutput,
            thinkingLevels = thinkingLevels
        )

        private fun toSpec(spec: Any): ZtTokenSpec = when (spec) {
            is ZtTokenSpec -> spec
            else -> ZtAlternatives(
                spec.toString().split('|')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            )
        }
    }

    private fun defineModel(block: ZtDefinitionBuilder.() -> Unit): ZtModelDefinition =
        ZtDefinitionBuilder().apply(block).build()

    private fun tokenRegex(pattern: String): ZtTokenSpec =
        ZtRegexSpec(Regex(pattern, RegexOption.IGNORE_CASE))

    private interface ZtTokenSpec {
        fun matches(token: String): Boolean
    }

    private class ZtAlternatives(private val options: Set<String>) : ZtTokenSpec {
        override fun matches(token: String): Boolean = token in options
    }

    private class ZtRegexSpec(private val regex: Regex) : ZtTokenSpec {
        override fun matches(token: String): Boolean = regex.matches(token)
    }

    private interface ZtTokenMatcher {
        fun score(modelId: String, tokens: List<String>): Int?
    }

    private object ZtMatchNone : ZtTokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? = null
    }

    private class ZtAndMatcher(private val matchers: List<ZtTokenMatcher>) : ZtTokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? {
            var total = 0
            for (matcher in matchers) {
                total += matcher.score(modelId, tokens) ?: return null
            }
            return total
        }
    }

    private class ZtExactMatcher(private val id: String) : ZtTokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? =
            if (modelId.equals(id, ignoreCase = true)) 1000 + tokens.size else null
    }

    /** 顺序匹配：token 序列按顺序出现即命中，得分为 spec 数量。 */
    private class ZtSequenceMatcher(private val specs: List<ZtTokenSpec>) : ZtTokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? {
            if (specs.isEmpty()) return null
            var specIndex = 0
            for (token in tokens) {
                if (specs[specIndex].matches(token)) {
                    specIndex++
                    if (specIndex == specs.size) return specs.size
                }
            }
            return null
        }
    }

    private class ZtNotMatcher(private val matcher: ZtSequenceMatcher) : ZtTokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? =
            if (matcher.score(modelId, tokens) == null) 0 else null
    }
}
