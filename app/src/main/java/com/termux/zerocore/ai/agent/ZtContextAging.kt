package com.termux.zerocore.ai.agent

/**
 * 上下文分级老化：会话越长，越老的信息保留得越粗。
 *
 * 与 ZtAgentContextBudget 的分工：
 *   - Budget 负责"超预算时丢最旧的轮"，是兜底的量控制；
 *   - Aging 负责"还没超预算也把老信息降精度"，是源头上的省。
 *
 * 全程本地完成，**不额外发一次请求**——这一点在按请求数限流的端点上很关键：
 * 让模型写摘要虽然压得更狠，但那要多发一次请求，省下的 token 换不来那次配额。
 *
 * 只改 content、不删消息，因为删除带 tool_calls 的 assistant 消息容易留下
 * 孤儿 tool 消息，OpenAI 兼容接口会报 "tool_call_id not found"。
 */
object ZtContextAging {

    private const val TAG = "ZtContextAging"

    private const val ROLE_SYSTEM = "system"
    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_TOOL = "tool"

    /** 折叠标记，让模型知道这里被截断了，而不是真的只有这么点输出。 */
    private const val MARKER = "[aged]"

    data class AgingConfig(
        /** 末尾最近多少轮完整保留。 */
        val freshTurns: Int = 6,
        /** 老于 freshTurns、新于该值的轮次：工具结果折叠成摘要行。 */
        val agedTurns: Int = 24,
        /** 新鲜区单条工具结果上限。 */
        val freshToolChars: Int = 8_000,
        /** 老化区工具结果折叠后保留的字符数。 */
        val agedToolChars: Int = 900,
        /** 远古区工具结果折叠后保留的字符数。 */
        val ancientToolChars: Int = 160,
        /** 老化区 assistant 正文截断长度。 */
        val agedAssistantChars: Int = 1_200,
        /** 远古区 assistant 正文截断长度。 */
        val ancientAssistantChars: Int = 400,
        /** 最多保留几条终端快照类 system 消息，更早的一律折叠。 */
        val keepSnapshots: Int = 1,
        /** 超过该轮数就在统计里标记"该开新会话了"。 */
        val suggestNewSessionTurns: Int = 30
    )

    data class AgingStats(
        val charsBefore: Int,
        val charsAfter: Int,
        val agedTools: Int,
        val agedAssistants: Int,
        val foldedSnapshots: Int,
        val deduped: Int,
        val turnCount: Int,
        val suggestNewSession: Boolean
    ) {
        val savedChars: Int get() = (charsBefore - charsAfter).coerceAtLeast(0)
        override fun toString(): String =
            "AgingStats(before=$charsBefore after=$charsAfter tools=$agedTools " +
                "assistants=$agedAssistants snapshots=$foldedSnapshots dedup=$deduped " +
                "turns=$turnCount newSession=$suggestNewSession)"
    }

    private enum class Tier { FRESH, AGED, ANCIENT }

    /**
     * 就地老化 [messages]。
     */
    fun age(
        messages: MutableList<ZtAgentAiChatClient.ChatMessage>,
        config: AgingConfig = AgingConfig()
    ): AgingStats {
        val before = ZtAgentContextBudget.totalChars(messages)
        if (messages.isEmpty()) {
            return AgingStats(0, 0, 0, 0, 0, 0, 0, false)
        }

        val tiers = computeTiers(messages, config)
        val seenToolResults = HashSet<String>()
        var agedTools = 0
        var agedAssistants = 0
        var deduped = 0

        for (i in messages.indices) {
            val m = messages[i]
            val tier = tiers[i]

            when (m.role) {
                ROLE_TOOL -> {
                    val content = m.content
                    if (content.isNullOrEmpty()) continue

                    // 同一份输出读了两次，第二次只留一行，避免重复占用。
                    val key = content
                    if (seenToolResults.contains(key)) {
                        messages[i] = m.copy(content = "$MARKER 与上文某次工具输出完全相同，已去重")
                        deduped++
                        continue
                    }
                    seenToolResults.add(key)

                    val limit = when (tier) {
                        Tier.FRESH -> config.freshToolChars
                        Tier.AGED -> config.agedToolChars
                        Tier.ANCIENT -> config.ancientToolChars
                    }
                    if (content.length > limit) {
                        val folded = foldToolResult(content, limit)
                        if (folded.length < content.length) {
                            messages[i] = m.copy(content = folded)
                            agedTools++
                        }
                    }
                }

                ROLE_ASSISTANT -> {
                    val content = m.content
                    if (content.isNullOrEmpty()) continue
                    val limit = when (tier) {
                        Tier.FRESH -> Int.MAX_VALUE
                        Tier.AGED -> config.agedAssistantChars
                        Tier.ANCIENT -> config.ancientAssistantChars
                    }
                    if (content.length > limit) {
                        messages[i] = m.copy(content = foldPlain(content, limit))
                        agedAssistants++
                    }
                }

                ROLE_USER -> {
                    // 用户消息通常短且是意图锚点，只在远古区兜底截断超长粘贴。
                    val content = m.content
                    if (tier == Tier.ANCIENT && content != null && content.length > 2_000) {
                        messages[i] = m.copy(content = foldPlain(content, 2_000))
                    }
                }
            }
        }

        val foldedSnapshots = foldOldSnapshots(messages, config)
        val after = ZtAgentContextBudget.totalChars(messages)
        val turns = countTurns(messages)

        val stats = AgingStats(
            charsBefore = before,
            charsAfter = after,
            agedTools = agedTools,
            agedAssistants = agedAssistants,
            foldedSnapshots = foldedSnapshots,
            deduped = deduped,
            turnCount = turns,
            suggestNewSession = turns >= config.suggestNewSessionTurns
        )
        if (stats.savedChars > 0) {
            android.util.Log.i(TAG, stats.toString())
        }
        return stats
    }

    /**
     * 逐条计算"轮龄"：从末尾往前数，遇到一条 user 或 assistant 记一轮。
     * 末尾的消息轮龄最小。
     */
    private fun computeTiers(
        messages: List<ZtAgentAiChatClient.ChatMessage>,
        config: AgingConfig
    ): Array<Tier> {
        val n = messages.size
        val tiers = Array(n) { Tier.FRESH }
        var turnsFromEnd = 0
        for (i in n - 1 downTo 0) {
            val role = messages[i].role
            tiers[i] = when {
                turnsFromEnd < config.freshTurns -> Tier.FRESH
                turnsFromEnd < config.agedTurns -> Tier.AGED
                else -> Tier.ANCIENT
            }
            if (role == ROLE_USER || role == ROLE_ASSISTANT) {
                turnsFromEnd++
            }
        }
        return tiers
    }

    private fun countTurns(messages: List<ZtAgentAiChatClient.ChatMessage>): Int {
        var turns = 0
        for (m in messages) {
            if (m.role == ROLE_USER || m.role == ROLE_ASSISTANT) turns++
        }
        return turns
    }

    /**
     * 折叠工具结果：保头 + 保尾，并保留首尾若干行以便模型看出结构。
     * 输出往往"开头是表头/路径、结尾是报错或结论"，中间是可再生的数据。
     */
    private fun foldToolResult(content: String, limit: Int): String {
        if (limit <= 0) return "$MARKER 输出已折叠（原文 " + content.length + " 字符）"
        if (content.length <= limit) return content

        val tail = (limit / 3).coerceAtLeast(0)
        val head = (limit - tail).coerceAtLeast(0)
        val headPart = content.substring(0, head)
        val tailPart = if (tail > 0) content.substring(content.length - tail) else ""
        val omitted = content.length - head - tail
        return headPart +
            "\n…$MARKER 折叠 $omitted 字符（原文共 ${content.length} 字符）…\n" +
            tailPart
    }

    private fun foldPlain(content: String, limit: Int): String {
        if (content.length <= limit) return content
        val tail = (limit / 4).coerceAtLeast(0)
        val head = (limit - tail).coerceAtLeast(0)
        val omitted = content.length - head - tail
        return content.substring(0, head) +
            "\n…$MARKER 折叠 $omitted 字符…\n" +
            content.substring(content.length - tail)
    }

    /**
     * 终端快照每轮刷新，是长会话里最纯粹的重复消耗：老快照对当前决策几乎没用。
     * 只保留最后 [keepSnapshots] 条，更早的压成一行。
     *
     * index 0 是主系统提示词，永远不动。
     */
    private fun foldOldSnapshots(
        messages: MutableList<ZtAgentAiChatClient.ChatMessage>,
        config: AgingConfig
    ): Int {
        val snapshotIndexes = ArrayList<Int>()
        for (i in 1 until messages.size) {
            val m = messages[i]
            if (m.role == ROLE_SYSTEM && ZtAgentContextBudget.isSnapshotMessage(m)) {
                snapshotIndexes.add(i)
            }
        }
        val keep = config.keepSnapshots.coerceAtLeast(0)
        if (snapshotIndexes.size <= keep) return 0

        val dropCount = snapshotIndexes.size - keep
        var folded = 0
        for (i in 0 until dropCount) {
            val idx = snapshotIndexes[i]
            val m = messages[idx]
            messages[idx] = m.copy(content = "$MARKER 早期终端快照已折叠")
            folded++
        }
        return folded
    }
}
