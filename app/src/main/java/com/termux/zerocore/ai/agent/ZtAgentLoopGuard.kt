package com.termux.zerocore.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 智能体工具调用死循环守卫。
 *
 * 识别四类循环，按优先级从高到低：
 *   1. unknown_tool_repeat       —— 连续重复调用不存在的（模型幻觉的）工具
 *   2. global_circuit_breaker    —— 任意工具：相同参数 + 相同结果反复出现
 *   3. known_poll_no_progress    —— 轮询工具结果冻结（如反复 read_terminal 无变化）
 *   4. generic_repeat            —— 相同参数反复调用，与结果无关
 *
 * 用法（工具执行前/后两个钩子）：
 *   val verdict = ZtAgentLoopGuard.check(name, args)
 *   if (verdict.level == ZtLoopLevel.CRITICAL) → 跳过执行，把 message 当工具错误结果回灌
 *   val after = ZtAgentLoopGuard.record(name, args, result, errorMsg, toolCallId)
 *   if (after.level == ZtLoopLevel.WARNING) → 把 message 追加到工具结果尾部提示模型
 *
 * 非线程安全；与 ZeroTermux 现有的单线程 agent 循环一致，只在 runInternal 的工作线程里调用。
 */
data class ZtToolCallRecord(
    val toolName: String,
    val argsHash: String,
    val resultHash: String? = null,
    val unknownToolName: String? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ZtLoopLevel { NONE, WARNING, CRITICAL }

data class ZtLoopCheckResult(
    val level: ZtLoopLevel,
    val message: String? = null,
    val warningKey: String? = null
) {
    companion object {
        val NONE = ZtLoopCheckResult(ZtLoopLevel.NONE)
    }
}

/**
 * 阈值契约：warningThreshold < criticalThreshold < globalCircuitBreakerThreshold
 * 构造函数里强制校验，避免调用方配出一组不会触发的阈值。
 */
data class ZtLoopConfig(
    val historySize: Int = 30,
    val warningThreshold: Int = 6,
    val unknownToolThreshold: Int = 3,
    val criticalThreshold: Int = 10,
    val globalCircuitBreakerThreshold: Int = 16,
    /** 空闲超过该时长后自动清空窗口，避免上一轮会话的历史误伤新一轮。 */
    val idleResetMs: Long = 10 * 60 * 1000L
) {
    init {
        require(warningThreshold > 0) { "warningThreshold must be positive" }
        require(warningThreshold < criticalThreshold) {
            "warningThreshold ($warningThreshold) must be < criticalThreshold ($criticalThreshold)"
        }
        require(criticalThreshold < globalCircuitBreakerThreshold) {
            "criticalThreshold ($criticalThreshold) must be < globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
        require(historySize >= globalCircuitBreakerThreshold) {
            "historySize ($historySize) must be >= globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
    }
}

class ZtAgentLoopGuard(initialConfig: ZtLoopConfig = ZtLoopConfig()) {

    companion object {
        private const val TAG = "ZtAgentLoopGuard"

        /** 纯展示性字段不参与哈希，否则模型每次微调标题就会让哈希失效。 */
        private val ARGS_HASH_IGNORED_KEYS: Set<String> = setOf("tool_title", "label")

        private val UNKNOWN_TOOL_RE_1 = Regex(
            """unknown tool[:\s]+["'`]?([a-zA-Z0-9_.\-]+)["'`]?""",
            RegexOption.IGNORE_CASE
        )
        private val UNKNOWN_TOOL_RE_2 = Regex(
            """tool\s+["'`]?([a-zA-Z0-9_.\-]+)["'`]?\s+(?:not found|is not available)""",
            RegexOption.IGNORE_CASE
        )
        private val UNKNOWN_TOOL_RE_BACKTICK = Regex(
            """unknown\s+tool[^\n]*?[`'"]([a-zA-Z0-9_.\-]+)[`'"]""",
            RegexOption.IGNORE_CASE
        )

        /**
         * 主会话使用的共享实例；子智能体各自 new 一个，避免互相污染滑动窗口。
         * 用 lazy 是因为它就在 companion 内部 —— 直接 `= ZtAgentLoopGuard()` 会让
         * companion 的初始化与实例构造互相纠缠。
         */
        val default: ZtAgentLoopGuard by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ZtAgentLoopGuard()
        }
    }

    @Volatile
    private var config: ZtLoopConfig = initialConfig

    private val history = ArrayDeque<ZtToolCallRecord>()
    // warningKey -> 已发射的告警桶，用于节流：同一告警每 warningThreshold 次才发一次，
    // 否则模型会在每一次工具调用后都被同一句话刷屏。
    private val warningBuckets = HashMap<String, Int>()

    @Volatile
    private var lastRecordAt: Long = 0L

    /** 连续被硬阻断的次数，供调用方决定是否提前终止整个 run。 */
    @Volatile
    var criticalBlocks: Int = 0
        private set

    fun configure(newConfig: ZtLoopConfig) {
        config = newConfig
        trimHistory()
    }

    /** 会话重置 / 清空聊天记录时调用。 */
    @Synchronized
    fun reset() {
        history.clear()
        warningBuckets.clear()
        criticalBlocks = 0
        lastRecordAt = 0L
    }

    @Synchronized
    internal fun historySnapshot(): List<ZtToolCallRecord> = history.toList()

    // ─── 执行前钩子 ──────────────────────────────────────────────────────────

    /**
     * 用滑动窗口判断即将执行的调用是否陷入循环。
     * 返回 CRITICAL 时，调用方必须跳过执行，并把 message 作为工具错误结果回灌给模型。
     */
    @Synchronized
    fun check(toolName: String, params: Map<String, Any?>): ZtLoopCheckResult {
        maybeResetIfIdle()
        val cfg = config
        val argsHash = argsHashFor(toolName, params)

        // 1. unknown_tool_repeat：最明确的信号，最先判定。
        val unknownStreak = countUnknownStreakFromTail(toolName)
        if (unknownStreak >= cfg.unknownToolThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: attempted unavailable tool '$toolName' " +
                "$unknownStreak times. Stop retrying that missing tool and answer without it."
            android.util.Log.w(TAG, "CRITICAL unknown_tool_repeat tool=$toolName streak=$unknownStreak")
            criticalBlocks++
            return ZtLoopCheckResult(ZtLoopLevel.CRITICAL, msg)
        }

        val noProgressStreak = getNoProgressStreak(toolName, argsHash)

        // 2. global_circuit_breaker：兜底，放在轮询规则之前，
        //    否则失控的非轮询循环会以更低的阈值溜过去。
        if (noProgressStreak >= cfg.globalCircuitBreakerThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: $toolName has repeated identical " +
                "no-progress outcomes $noProgressStreak times. Execution blocked by " +
                "global circuit breaker."
            android.util.Log.w(TAG, "CRITICAL circuit_breaker tool=$toolName streak=$noProgressStreak")
            criticalBlocks++
            return ZtLoopCheckResult(ZtLoopLevel.CRITICAL, msg)
        }

        // 3. known_poll_no_progress：轮询工具用更严格的阈值，
        //    因为"反复轮询而无进展"是最典型的烧 token 场景。
        if (isPollTool(toolName)) {
            if (noProgressStreak >= cfg.criticalThreshold) {
                val msg = "[LOOP BLOCKED] CRITICAL: Called $toolName $noProgressStreak " +
                    "times with identical no-progress results. Stop reading and either " +
                    "(1) wait longer before the next read, or (2) report the task as failed."
                android.util.Log.w(TAG, "CRITICAL poll_no_progress tool=$toolName streak=$noProgressStreak")
                criticalBlocks++
                return ZtLoopCheckResult(ZtLoopLevel.CRITICAL, msg)
            }
            if (noProgressStreak >= cfg.warningThreshold) {
                val msg = "[LOOP WARNING] You have called $toolName $noProgressStreak " +
                    "times with no progress. Stop polling and either (1) increase wait " +
                    "time, or (2) report the task as failed."
                android.util.Log.d(TAG, "WARNING poll_no_progress tool=$toolName streak=$noProgressStreak")
                return ZtLoopCheckResult(ZtLoopLevel.WARNING, msg, "poll:$toolName:$argsHash")
            }
        }

        // 4. generic_repeat：非轮询工具，统计窗口内（非连续）相同参数的调用次数，
        //    这样偶发失败但确有进展的调用会自然滑出窗口。
        if (!isPollTool(toolName)) {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= cfg.warningThreshold) {
                val msg = "[LOOP WARNING] You have called $toolName $totalCount times " +
                    "with identical arguments. If this is not making progress, stop " +
                    "retrying and report the task as failed."
                android.util.Log.d(TAG, "WARNING generic_repeat tool=$toolName count=$totalCount")
                return ZtLoopCheckResult(ZtLoopLevel.WARNING, msg, "repeat:$toolName:$argsHash")
            }
        }

        return ZtLoopCheckResult.NONE
    }

    // ─── 执行后钩子 ──────────────────────────────────────────────────────────

    /**
     * 把刚完成的调用记入窗口，并决定是否要在本次工具结果尾部追加一条告警。
     * CRITICAL 只由 check() 在执行前给出；record() 最多返回 WARNING。
     */
    @Synchronized
    fun record(
        toolName: String,
        params: Map<String, Any?>,
        result: String?,
        errorMessage: String? = null,
        toolCallId: String? = null
    ): ZtLoopCheckResult {
        val cfg = config
        val argsHash = argsHashFor(toolName, params)
        val resultHash = resultHashFor(result, errorMessage)
        val unknownToolName = extractUnknownToolName(errorMessage)

        history.addLast(
            ZtToolCallRecord(
                toolName = toolName,
                argsHash = argsHash,
                resultHash = resultHash,
                unknownToolName = unknownToolName,
                toolCallId = toolCallId
            )
        )
        lastRecordAt = System.currentTimeMillis()
        while (history.size > cfg.historySize) history.removeFirst()

        // 记入后重新评估，让告警文案反映包含本次在内的最新计数。
        if (isPollTool(toolName)) {
            val streak = getNoProgressStreak(toolName, argsHash)
            if (streak in cfg.warningThreshold until cfg.criticalThreshold) {
                val key = "poll:$toolName:$argsHash"
                if (shouldEmitWarning(key, streak)) {
                    val msg = "[LOOP WARNING] You have called $toolName $streak times " +
                        "with no progress. Stop polling and either (1) increase wait " +
                        "time, or (2) report the task as failed."
                    return ZtLoopCheckResult(ZtLoopLevel.WARNING, msg, key)
                }
            }
        } else {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= cfg.warningThreshold) {
                val key = "repeat:$toolName:$argsHash"
                if (shouldEmitWarning(key, totalCount)) {
                    val msg = "[LOOP WARNING] You have called $toolName $totalCount " +
                        "times with identical arguments. If this is not making " +
                        "progress, stop retrying and report the task as failed."
                    return ZtLoopCheckResult(ZtLoopLevel.WARNING, msg, key)
                }
            }
        }

        return ZtLoopCheckResult.NONE
    }

    /** 一次成功的工具调用会证明"有进展"，清零连续硬阻断计数。 */
    @Synchronized
    fun noteProgress() {
        criticalBlocks = 0
    }

    // ─── 策略辅助 ────────────────────────────────────────────────────────────

    /** 从最新往回数，尾部连续多少条是同一个幻觉工具名。 */
    private fun countUnknownStreakFromTail(toolName: String): Int {
        var streak = 0
        for (rec in history.reversed()) {
            val unk = rec.unknownToolName ?: break
            if (unk == toolName) streak++ else break
        }
        return streak
    }

    /**
     * 统计最近的 (toolName, argsHash) 相同且结果哈希一致的记录数。
     * 其它工具的记录跳过（不打断计数），但目标工具的结果一旦变化就停止计数 —— 这就是"看到进展"的信号。
     */
    private fun getNoProgressStreak(toolName: String, argsHash: String): Int {
        var streak = 0
        var pinnedHash: String? = null
        for (rec in history.reversed()) {
            if (rec.toolName != toolName || rec.argsHash != argsHash) continue
            val rh = rec.resultHash ?: break
            if (pinnedHash == null) {
                pinnedHash = rh
                streak++
            } else if (rh == pinnedHash) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    /**
     * ZeroTermux 里真正会被反复轮询的只有 `read_terminal`（AI 反复读屏等命令落地）。
     * `send_terminal_command` 即使参数相同也可能是有意的重试，交给 generic_repeat 处理。
     */
    private fun isPollTool(toolName: String): Boolean = toolName == "read_terminal"

    /** 同一告警每 warningThreshold 次才发射一次。 */
    private fun shouldEmitWarning(warningKey: String, currentCount: Int): Boolean {
        val bucket = currentCount / config.warningThreshold
        val last = warningBuckets[warningKey]
        if (last == bucket) return false
        warningBuckets[warningKey] = bucket
        return true
    }

    private fun maybeResetIfIdle() {
        val idle = config.idleResetMs
        if (idle > 0 && lastRecordAt > 0 && System.currentTimeMillis() - lastRecordAt > idle) {
            android.util.Log.d(TAG, "idle window expired, resetting history")
            history.clear()
            warningBuckets.clear()
            criticalBlocks = 0
        }
    }

    private fun trimHistory() {
        while (history.size > config.historySize) history.removeFirst()
    }

    // ─── 哈希 / 解析 ─────────────────────────────────────────────────────────

    private fun argsHashFor(toolName: String, params: Map<String, Any?>): String {
        val filtered = if (params.keys.any { it in ARGS_HASH_IGNORED_KEYS }) {
            params.filterKeys { it !in ARGS_HASH_IGNORED_KEYS }
        } else {
            params
        }
        return sha256("$toolName:${stableJson(filtered)}")
    }

    /** 稳定 JSON：每一层键都按字典序排序，保证插入顺序不同的等价 Map 哈希一致。 */
    private fun stableJson(value: Any?): String = buildString { appendStable(value) }

    private fun StringBuilder.appendStable(value: Any?) {
        when (value) {
            null -> append("null")
            is Map<*, *> -> {
                append('{')
                value.entries
                    .map { it.key?.toString().orEmpty() to it.value }
                    .sortedBy { it.first }
                    .forEachIndexed { i, (k, v) ->
                        if (i > 0) append(',')
                        append(JSONObject.quote(k)); append(':'); appendStable(v)
                    }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    appendStable(v)
                }
                append(']')
            }
            is Array<*> -> appendStable(value.toList())
            is String -> append(JSONObject.quote(value))
            is Number, is Boolean -> append(value.toString())
            is JSONObject -> appendStable(value.toMap())
            is JSONArray -> appendStable(value.toList())
            else -> append(JSONObject.quote(value.toString()))
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = HashMap<String, Any?>(length())
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = unwrap(get(k))
        }
        return out
    }

    private fun JSONArray.toList(): List<Any?> {
        val out = ArrayList<Any?>(length())
        for (i in 0 until length()) out.add(unwrap(get(i)))
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> v.toMap()
        is JSONArray -> v.toList()
        else -> v
    }

    private fun resultHashFor(result: String?, errorMessage: String?): String {
        val payload = buildString {
            append("err=")
            append(errorMessage ?: "")
            append("out=")
            append(result ?: "")
        }
        return sha256(payload)
    }

    /**
     * 覆盖三种文案形态：
     *   "unknown tool: foobar"          （OpenAI 风格）
     *   "tool 'foobar' not found"       （部分兼容层）
     *   "Error: unknown tool `foobar`"  （ZeroTermux ZtAgentAiToolExecutor 的实际返回）
     * 均忽略大小写与引号/反引号。
     */
    private fun extractUnknownToolName(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        val m1 = UNKNOWN_TOOL_RE_1.find(errorMessage)
        if (m1 != null) return m1.groupValues[1]
        val m2 = UNKNOWN_TOOL_RE_2.find(errorMessage)
        if (m2 != null) return m2.groupValues[1]
        val m3 = UNKNOWN_TOOL_RE_BACKTICK.find(errorMessage)
        if (m3 != null) return m3.groupValues[1]
        return null
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes: ByteArray = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
