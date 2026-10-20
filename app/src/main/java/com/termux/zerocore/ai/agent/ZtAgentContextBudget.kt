package com.termux.zerocore.ai.agent

/**
 * 智能体上下文预算：在把消息列表交给模型之前做两层裁剪，避免长会话把 token 烧穿。
 *
 * 每次请求前做两层裁剪：折叠超长工具结果，再丢最旧的轮次。
 * 但按 ZeroTermux 的数据结构落地：
 *   - 只依赖 ZtAgentAiChatClient.ChatMessage，不引入 SessionWorkspace / 数据库；
 *   - 终端快照是 System 消息且每轮刷新，裁剪时优先牺牲它们；
 *   - 保证"assistant(tool_calls) 与它对应的 tool 结果"成对删除，否则 OpenAI 兼容接口
 *     会报 "tool_call_id not found" 之类的错。
 *
 * 两层策略，按顺序执行：
 *   1. 折叠超长工具结果（保头 60% + 尾 40%，中间打省略号）—— 信息密度最高的一步；
 *   2. 仍超预算则从最旧的"轮"开始丢弃，但绝不丢首条 system 提示词与最后一条用户消息。
 */
object ZtAgentContextBudget {

    private const val TAG = "ZtAgentContextBudget"

    private const val ROLE_SYSTEM = "system"
    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_TOOL = "tool"

    private const val SNAPSHOT_PREFIX_FALLBACK = "Terminal snapshot"

    data class BudgetConfig(
        /** 整个请求体的字符预算，默认约合 30k token。 */
        val maxPromptChars: Int = 120_000,
        /** 单条工具结果超过该长度就折叠。默认 8000 字符。 */
        val maxToolResultChars: Int = 8_000,
        /** 折叠后保留的尾部字符数（默认取 maxToolResultChars 的 40%）。 */
        val keepTailChars: Int = 3_200,
        /** 预算兜底：最多丢弃多少条消息，防止极端情况下把循环卡死。 */
        val maxDroppedMessages: Int = 200
    )

    data class TrimStats(
        val charsBefore: Int,
        val charsAfter: Int,
        val truncatedResults: Int,
        val droppedMessages: Int
    ) {
        val savedChars: Int get() = (charsBefore - charsAfter).coerceAtLeast(0)
        override fun toString(): String =
            "TrimStats(before=$charsBefore, after=$charsAfter, truncated=$truncatedResults, dropped=$droppedMessages)"
    }

    /**
     * 粗略 token 估算：CJK 按 1 字 ≈ 1 token，其余按 4 字符 ≈ 1 token。
     * 只用于裁剪决策，不用于计费。
     */
    fun estimateTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var cjk = 0
        for (ch in text) {
            if (ch in '\u4e00'..'\u9fff' || ch in '\u3000'..'\u303f' || ch in '\uff00'..'\uffef') {
                cjk++
            }
        }
        val others = text.length - cjk
        return cjk + (others + 3) / 4
    }

    fun totalChars(messages: List<ZtAgentAiChatClient.ChatMessage>): Int {
        var sum = 0
        for (m in messages) {
            sum += m.content?.length ?: 0
            m.toolCalls?.forEach { tc -> sum += tc.arguments.length + tc.name.length }
        }
        return sum
    }

    fun totalTokens(messages: List<ZtAgentAiChatClient.ChatMessage>): Int {
        var sum = 0
        for (m in messages) {
            sum += estimateTokens(m.content)
            m.toolCalls?.forEach { tc -> sum += estimateTokens(tc.arguments) + estimateTokens(tc.name) }
        }
        return sum
    }

    /**
     * 就地裁剪 [messages]。返回统计信息，便于在 UI 上提示"本轮裁掉了 N 条历史"。
     */
    @Synchronized
    fun trim(
        messages: MutableList<ZtAgentAiChatClient.ChatMessage>,
        config: BudgetConfig = BudgetConfig()
    ): TrimStats {
        val charsBefore = totalChars(messages)
        var truncated = 0

        // 第一层：折叠超长工具结果。
        for (i in messages.indices) {
            val m = messages[i]
            if (m.role == ROLE_TOOL) {
                val content = m.content
                if (content != null && content.length > config.maxToolResultChars) {
                    messages[i] = m.copy(content = collapse(content, config.maxToolResultChars, config.keepTailChars))
                    truncated++
                }
            }
        }

        // 第二层：从最旧的一轮开始丢，直到进入预算。
        var dropped = 0
        var guard = 0
        while (totalChars(messages) > config.maxPromptChars && guard++ < config.maxDroppedMessages) {
            val idx = firstDroppableIndex(messages)
            if (idx < 0) break
            val removed = removeTurnAt(messages, idx)
            if (removed == 0) break
            dropped += removed
        }

        val stats = TrimStats(charsBefore, totalChars(messages), truncated, dropped)
        if (stats.savedChars > 0) {
            android.util.Log.i(TAG, stats.toString())
        }
        return stats
    }

    /**
     * 可丢弃区间的起点：[1, lastUserIndex)。
     * index 0 是系统提示词，最后一条用户消息及其之后的工具回灌都不动。
     */
    private fun firstDroppableIndex(messages: List<ZtAgentAiChatClient.ChatMessage>): Int {
        if (messages.size <= 2) return -1
        val lastUserIndex = messages.indexOfLast { it.role == ROLE_USER }
        val upper = if (lastUserIndex <= 0) messages.lastIndex else lastUserIndex - 1
        for (i in 1..upper) {
            if (messages[i].role != ROLE_USER || i != lastUserIndex) return i
        }
        return -1
    }

    /**
     * 删除 [index] 处的消息；若它是带 tool_calls 的 assistant 消息，
     * 顺带删除紧随其后、属于同一批调用的 tool 结果，避免留下孤儿 tool 消息。
     */
    private fun removeTurnAt(
        messages: MutableList<ZtAgentAiChatClient.ChatMessage>,
        index: Int
    ): Int {
        if (index !in messages.indices) return 0
        val target = messages[index]
        var removed = 0
        messages.removeAt(index)
        removed++

        val ids = target.toolCalls?.map { it.id }?.toSet()
        if (!ids.isNullOrEmpty()) {
            var cursor = index
            while (cursor < messages.size) {
                val m = messages[cursor]
                if (m.role == ROLE_TOOL && m.toolCallId in ids) {
                    messages.removeAt(cursor)
                    removed++
                } else {
                    break
                }
            }
        }
        return removed
    }

    /** 保头 + 保尾，中间用省略号标注被折叠的字节数。 */
    private fun collapse(content: String, maxChars: Int, tailChars: Int): String {
        val keepTail = tailChars.coerceAtMost(maxChars / 2).coerceAtLeast(0)
        val keepHead = (maxChars - keepTail).coerceAtLeast(0)
        if (keepHead <= 0) return content.take(maxChars)
        val head = content.take(keepHead)
        val tail = if (keepTail > 0) content.takeLast(keepTail) else ""
        val omitted = (content.length - head.length - tail.length).coerceAtLeast(0)
        return if (omitted <= 0) {
            content
        } else {
            "$head\n\n…[truncated $omitted chars by context budget]…\n\n$tail"
        }
    }

    /** 判断是否是每轮刷新的终端快照消息，供调试/日志使用。 */
    fun isSnapshotMessage(message: ZtAgentAiChatClient.ChatMessage): Boolean {
        return message.role == ROLE_SYSTEM && (
            message.content?.startsWith(SNAPSHOT_PREFIX_FALLBACK) == true ||
                message.content?.contains("snapshot", ignoreCase = true) == true
            )
    }
}
