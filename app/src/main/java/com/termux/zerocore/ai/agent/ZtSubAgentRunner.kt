package com.termux.zerocore.ai.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 子智能体：主会话把一块独立任务派出去，子智能体在自己的上下文与轮次预算里跑完，
 * 只把最终结论回给主会话。核心价值是上下文隔离。
 *
 * 四条硬约束：
 *  1. 子智能体不能再派子智能体，否则一个坏提示就能递归出 N 层；
 *  2. 独立的循环守卫实例，避免子任务的重复调用误触发主会话熔断；
 *  3. 轮次预算远小于主会话（默认 8，上限 20），跑飞了损失可控；
 *  4. 失败不抛异常，返回说明文本让主会话决定重试还是换思路。
 */
class ZtSubAgentRunner(
    private val client: ZtAgentAiChatClient,
    private val terminalEnabled: Boolean,
    private val ztControlEnabled: Boolean,
    private val filesystemEnabled: Boolean
) {

    companion object {
        private const val TAG = "ZtSubAgentRunner"
        const val DEFAULT_ROUNDS = 8
        const val MAX_ROUNDS = 20
        const val TOOL_NAME = "dispatch_subagent"

        // ─── 主会话侧的工具定义与分发 ────────────────────────────────────────────

        fun toolDefinition(): JSONObject = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", TOOL_NAME)
                    .put(
                        "description",
                        "Delegate ONE self-contained sub-task to a sub-agent with its own " +
                            "context window. Use it for work that would otherwise flood the main " +
                            "conversation: surveying a directory, reading many files, or iterating " +
                            "on a command. Returns only the final result."
                    )
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put(
                                        "task",
                                        JSONObject()
                                            .put("type", "string")
                                            .put(
                                                "description",
                                                "A complete, self-contained task description. " +
                                                    "The sub-agent sees nothing else, so include " +
                                                    "every path and constraint it needs."
                                            )
                                    )
                                    .put(
                                        "context",
                                        JSONObject()
                                            .put("type", "string")
                                            .put(
                                                "description",
                                                "Optional background the sub-agent needs."
                                            )
                                    )
                                    .put(
                                        "max_rounds",
                                        JSONObject()
                                            .put("type", "integer")
                                            .put("description", "Tool rounds, 1..$MAX_ROUNDS. Default $DEFAULT_ROUNDS.")
                                    )
                            )
                            .put("required", JSONArray().put("task"))
                    )
            )

        /** 解析主会话发来的参数。 */
        fun parse(arguments: String): SubTask {
            val obj = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            return SubTask(
                task = obj.optString("task", ""),
                context = obj.optString("context", ""),
                rounds = obj.optInt("max_rounds", DEFAULT_ROUNDS).let {
                    if (it <= 0) DEFAULT_ROUNDS else it
                }
            )
        }

        /** 把结果格式化成给主会话看的工具返回。 */
        fun format(result: SubResult): String {
            if (result.isError) {
                return "Error: sub-agent failed — ${result.error}"
            }
            return buildString {
                append(result.text)
                append("\n\n[sub-agent: ${result.roundsUsed} round(s), ${result.toolCalls} tool call(s)]")
            }
        }
    }

    data class SubTask(
        val task: String,
        val context: String = "",
        val rounds: Int = DEFAULT_ROUNDS
    )

    data class SubResult(
        val text: String,
        val roundsUsed: Int,
        val toolCalls: Int,
        val error: String? = null
    ) {
        val isError: Boolean get() = error != null
        override fun toString(): String =
            "SubResult(rounds=$roundsUsed, tools=$toolCalls, error=$error)"
    }

    /** 同步执行，必须在工作线程调用。 */
    fun run(subTask: SubTask): SubResult {
        val task = subTask.task.trim()
        if (task.isEmpty()) {
            return SubResult("", 0, 0, "task is required")
        }
        val maxRounds = subTask.rounds.coerceIn(1, MAX_ROUNDS)
        val guard = ZtAgentLoopGuard(ZtAgentAiConfigHelper.loopConfig())

        val messages = mutableListOf(
            ZtAgentAiChatClient.ChatMessage(
                role = "system",
                content = buildSystemPrompt(subTask.context)
            ),
            ZtAgentAiChatClient.ChatMessage(role = "user", content = task)
        )

        val tools = buildTools()
        var roundsUsed = 0
        var toolCalls = 0

        try {
            while (roundsUsed < maxRounds) {
                val result = client.chatCompletionSync(messages, tools)
                if (result.error != null) {
                    return SubResult("", roundsUsed, toolCalls, result.error)
                }
                if (result.toolCalls.isEmpty()) {
                    val text = result.content?.trim().orEmpty()
                    return SubResult(
                        text = text.ifBlank { "(sub-agent produced no output)" },
                        roundsUsed = roundsUsed,
                        toolCalls = toolCalls
                    )
                }
                roundsUsed++
                messages.add(
                    ZtAgentAiChatClient.ChatMessage(
                        role = "assistant",
                        content = result.content,
                        toolCalls = result.toolCalls
                    )
                )
                for (call in result.toolCalls) {
                    val args = parseArgs(call.arguments)

                    // 执行前：命中 CRITICAL 就跳过真实执行，把说明回灌给子智能体。
                    val verdict = guard.check(call.name, args)
                    val toolOut = if (verdict.level == ZtLoopLevel.CRITICAL) {
                        verdict.message ?: "blocked by loop guard"
                    } else {
                        val executed = ZtAgentAiToolExecutor.execute(
                            call, terminalEnabled, ztControlEnabled, filesystemEnabled
                        )
                        toolCalls++
                        val after = guard.record(
                            toolName = call.name,
                            params = args,
                            result = executed,
                            errorMessage = executed.takeIf {
                                it.startsWith("Error", ignoreCase = true)
                            },
                            toolCallId = call.id
                        )
                        if (after.level == ZtLoopLevel.WARNING && !after.message.isNullOrBlank()) {
                            "$executed\n\n${after.message}"
                        } else {
                            executed
                        }
                    }

                    messages.add(
                        ZtAgentAiChatClient.ChatMessage(
                            role = "tool",
                            content = toolOut,
                            toolCallId = call.id
                        )
                    )
                }
                // 子任务的上下文也要裁，否则几轮工具结果就能把窗口塞满。
                if (ZtAgentAiConfigHelper.isContextAgingEnabled()) {
                    ZtContextAging.age(messages, ZtAgentAiConfigHelper.contextAging())
                }
                ZtAgentContextBudget.trim(messages, ZtAgentAiConfigHelper.contextBudget())
            }
            return SubResult(
                "", roundsUsed, toolCalls,
                "sub-agent hit its round limit ($maxRounds) without producing a final answer"
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "sub-agent error: ${e.message}")
            return SubResult("", roundsUsed, toolCalls, e.message ?: "sub-agent failed")
        }
    }

    private fun buildSystemPrompt(context: String): String = buildString {
        appendLine("You are a focused sub-agent working on ONE well-scoped task.")
        appendLine("Work independently, then reply with the final result only —")
        appendLine("no narration of intermediate steps unless the task asks for it.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Stay strictly within the assigned task. Do not broaden scope.")
        appendLine("- You cannot dispatch further sub-agents.")
        appendLine("- If the task is impossible, say so and explain why in one paragraph.")
        if (terminalEnabled) {
            appendLine("- Commands run in a Termux terminal. Prefer non-destructive commands.")
        }
        appendLine("- Reply in the same language as the task.")
        if (context.isNotBlank()) {
            appendLine()
            appendLine("# Context from the main session")
            appendLine(context.trim())
        }
    }.trim()

    /**
     * 子智能体的工具集：沿用主会话的权限开关，但剔除 `dispatch_subagent`
     * 与记忆写入（记忆归主会话管，避免子任务污染长期记忆）。
     *
     * 注意：`definitions()` 内部已经把 MCP 工具加进去了，这里**不能**再调一次
     * `ZtMcpToolBridge.addTools`，否则同一批 MCP 工具会出现两次，
     * 模型会看到重名的 function 定义。
     */
    private fun buildTools(): JSONArray {
        val tools = JSONArray()
        val base = ZtAgentAiTools.definitions(
            terminalEnabled = terminalEnabled,
            ztControlEnabled = ztControlEnabled,
            filesystemEnabled = filesystemEnabled
        )
        for (i in 0 until base.length()) {
            val t = base.optJSONObject(i) ?: continue
            val name = t.optJSONObject("function")?.optString("name").orEmpty()
            if (name == TOOL_NAME) continue
            if (name.startsWith("memory_")) continue
            tools.put(t)
        }
        return tools
    }

    private fun parseArgs(arguments: String): Map<String, Any?> {
        return try {
            val obj = JSONObject(arguments.ifBlank { "{}" })
            val out = HashMap<String, Any?>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = if (obj.isNull(k)) null else obj.get(k)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
