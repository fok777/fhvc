package com.termux.zerocore.ai.agent

import com.example.xh_lib.utils.LogUtils
import com.example.xh_lib.utils.UUtils
import com.termux.R
import com.termux.zerocore.ai.config.ZtAiStrings
import com.termux.zerocore.utils.ZtLocaleStrings
import org.json.JSONObject

class ZtAgentAiAgentRunner(
    private val client: ZtAgentAiChatClient,
    private val terminalEnabled: Boolean,
    private val ztControlEnabled: Boolean,
    private val filesystemEnabled: Boolean = false
) {
    interface Callback {
        fun onToolStep(label: String, detail: String)
        /** 工具调用轮次进度：current 为已执行轮数，max 为设置中的上限。 */
        fun onToolRoundProgress(current: Int, max: Int) {}
        fun onComplete(content: String)
        fun onError(message: String)
        fun isCancelled(): Boolean
    }

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var subAgentRunnerRef: ZtSubAgentRunner? = null

    fun cancel() {
        worker?.interrupt()
        worker = null
    }

    fun run(history: List<ZtAgentAiChatClient.ChatMessage>, callback: Callback) {
        cancel()
        val thread = Thread {
            try {
                runInternal(history, callback)
            } catch (e: InterruptedException) {
                LogUtils.e(TAG, "agent interrupted")
            } catch (e: Exception) {
                LogUtils.e(TAG, "agent error: $e")
                if (!callback.isCancelled()) {
                    post { callback.onError(e.message ?: "Agent error") }
                }
            }
        }
        worker = thread
        thread.start()
    }

    private fun runInternal(
        history: List<ZtAgentAiChatClient.ChatMessage>,
        callback: Callback
    ) {
        val workingMessages = buildWorkingMessages(history)
        val tools = if (ZtAgentAiConfigHelper.shouldUseAgentRunner()) {
            ZtAgentAiTools.definitions(terminalEnabled, ztControlEnabled, filesystemEnabled)
        } else {
            null
        }
        val loopGuardEnabled = ZtAgentAiConfigHelper.isLoopGuardEnabled()
        val budgetConfig = ZtAgentAiConfigHelper.contextBudget()
        if (loopGuardEnabled) {
            // 会话首轮（历史里还没有任何 assistant 消息）视为新会话，清空上一轮的滑动窗口。
            if (history.none { it.role == ROLE_ASSISTANT }) {
                ZtAgentLoopGuard.default.reset()
            }
            ZtAgentLoopGuard.default.configure(ZtAgentAiConfigHelper.loopConfig())
        }
        var rounds = 0
        val maxToolRounds = ZtAgentAiConfigHelper.maxToolRounds()
        post { callback.onToolRoundProgress(0, maxToolRounds) }
        while (rounds < maxToolRounds) {
            if (callback.isCancelled()) return
            if (loopGuardEnabled && ZtAgentLoopGuard.default.criticalBlocks >= MAX_CRITICAL_BLOCKS) {
                // 已经被硬阻断多次，继续跑只会烧 token，直接收尾并给出可操作的提示。
                post {
                    if (!callback.isCancelled()) {
                        callback.onError(LOOP_BLOCKED_MESSAGE)
                    }
                }
                return
            }
            if (terminalEnabled) {
                appendFreshTerminalSnapshot(workingMessages)
            }
            ZtAgentContextBudget.trim(workingMessages, budgetConfig)
            val result = client.chatCompletionSync(workingMessages, tools)
            if (callback.isCancelled()) return
            if (result.error != null) {
                post {
                    if (!callback.isCancelled()) {
                        callback.onError(result.error)
                    }
                }
                return
            }
            if (result.toolCalls.isNotEmpty()) {
                rounds++
                post { callback.onToolRoundProgress(rounds, maxToolRounds) }
                workingMessages.add(
                    ZtAgentAiChatClient.ChatMessage(
                        role = ROLE_ASSISTANT,
                        content = result.content,
                        toolCalls = result.toolCalls
                    )
                )
                for (toolCall in result.toolCalls) {
                    if (callback.isCancelled()) return
                    executeToolCallWithUi(toolCall, callback, workingMessages)
                }
                continue
            }
            val reply = result.content?.trim().orEmpty()
            post {
                if (!callback.isCancelled()) {
                    callback.onComplete(reply)
                }
            }
            return
        }
        post {
            if (callback.isCancelled()) return@post
            callback.onError(
                ZtLocaleStrings.format(
                    R.string.zt_agent_ai_tool_limit,
                    maxToolRounds
                )
            )
        }
    }

    private fun appendFreshTerminalSnapshot(messages: MutableList<ZtAgentAiChatClient.ChatMessage>) {
        messages.removeAll { it.role == ROLE_SYSTEM && it.content?.startsWith(SNAPSHOT_PREFIX) == true }
        messages.add(
            ZtAgentAiChatClient.ChatMessage(
                role = ROLE_SYSTEM,
                content = ZtAgentAiTerminalExecutor.captureSnapshot(3000)
            )
        )
    }

    private fun executeToolCallWithUi(
        toolCall: ZtAgentAiChatClient.ToolCall,
        callback: Callback,
        workingMessages: MutableList<ZtAgentAiChatClient.ChatMessage>
    ) {
        val label = ZtAgentAiToolExecutor.statusLabel(toolCall.name)
        val preview = toolCallPreview(toolCall)
        val guardEnabled = ZtAgentAiConfigHelper.isLoopGuardEnabled()
        val args = parseToolArgs(toolCall.arguments)

        // 执行前：命中 CRITICAL 时跳过真实执行，把阻断说明当作工具错误结果回灌给模型。
        if (guardEnabled) {
            val verdict = ZtAgentLoopGuard.default.check(toolCall.name, args)
            if (verdict.level == ZtLoopLevel.CRITICAL) {
                val blockMessage = verdict.message ?: LOOP_BLOCKED_MESSAGE
                android.util.Log.w(TAG, "loop guard blocked ${toolCall.name}")
                post { callback.onToolStep(label, blockMessage) }
                workingMessages.add(
                    ZtAgentAiChatClient.ChatMessage(
                        role = ROLE_TOOL,
                        content = blockMessage,
                        toolCallId = toolCall.id
                    )
                )
                return
            }
        }

        val toolResult = ZtAgentAiToolExecutor.execute(
            toolCall,
            terminalEnabled,
            ztControlEnabled,
            filesystemEnabled,
            subAgentRunner()
        )
        android.util.Log.i(TAG, "tool ${toolCall.name} result=$toolResult")

        // 执行后：把结果记入滑动窗口，必要时在结果尾部追加一条告警提示。
        var finalResult = toolResult
        if (guardEnabled) {
            val errorMessage = toolResult.takeIf { it.startsWith("Error", ignoreCase = true) }
            val after = ZtAgentLoopGuard.default.record(
                toolName = toolCall.name,
                params = args,
                result = toolResult,
                errorMessage = errorMessage,
                toolCallId = toolCall.id
            )
            if (after.level == ZtLoopLevel.WARNING && !after.message.isNullOrBlank()) {
                finalResult = "$toolResult\n\n${after.message}"
            }
            if (errorMessage == null) {
                ZtAgentLoopGuard.default.noteProgress()
            }
        }

        when (toolCall.name) {
            "send_terminal_command", "run_zt_command" -> {
                post { callback.onToolStep(label, preview) }
                post {
                    callback.onToolStep(
                        ZtLocaleStrings.getString(R.string.zt_agent_ai_tool_read_after_send),
                        ""
                    )
                }
            }
            "read_terminal", "send_terminal_key" -> {
                post { callback.onToolStep(label, preview) }
            }
            else -> {
                val detail = when {
                    preview.isNotBlank() && finalResult.isNotBlank() -> "$preview\n\n$finalResult"
                    finalResult.isNotBlank() -> finalResult
                    else -> preview
                }
                post { callback.onToolStep(label, detail) }
            }
        }

        workingMessages.add(
            ZtAgentAiChatClient.ChatMessage(
                role = ROLE_TOOL,
                content = finalResult,
                toolCallId = toolCall.id
            )
        )
    }

    /**
     * 子智能体执行器，懒创建。只在开启了子智能体时才建，避免每次对话多一个对象。
     * 传给 executor 后，`dispatch_subagent` 才能被分发；子智能体内部递归调用时
     * 传 null，所以不会出现子又派子。
     */
    private fun subAgentRunner(): ZtSubAgentRunner? {
        if (!ZtAgentAiConfigHelper.isSubAgentEnabled()) return null
        val runner = subAgentRunnerRef ?: ZtSubAgentRunner(
            client = client,
            terminalEnabled = terminalEnabled,
            ztControlEnabled = ztControlEnabled,
            filesystemEnabled = filesystemEnabled
        ).also { subAgentRunnerRef = it }
        return runner
    }

    /** 把工具的 arguments JSON 摊平成 Map，供循环守卫做稳定哈希。 */
    private fun parseToolArgs(arguments: String): Map<String, Any?> {

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

    private fun toolCallPreview(toolCall: ZtAgentAiChatClient.ToolCall): String {
        return try {
            val args = JSONObject(toolCall.arguments.ifBlank { "{}" })
            when (toolCall.name) {
                "send_terminal_command" -> {
                    val cmd = args.optString("command", "").trim()
                    if (cmd.isEmpty()) "" else "→ $cmd"
                }
                "send_terminal_key" -> args.optString("key", "").trim()
                "run_zt_command" -> {
                    val cmd = args.optString("command", "").trim()
                    if (cmd.isEmpty()) "" else "→ zt $cmd"
                }
                "run_zerotermux_zt" -> {
                    val cmd = args.optString("command", "").trim()
                    if (cmd.isEmpty()) "" else "→ zt $cmd"
                }
                "set_zerotermux_config" -> {
                    val key = args.optString("key", "").trim()
                    val value = args.optString("value", "").trim()
                    if (key.isEmpty()) "" else "→ $key = $value"
                }
                "open_zerotermux_page" -> {
                    val page = args.optString("page_id", "").trim()
                    if (page.isEmpty()) "→ list pages" else "→ open $page"
                }
                "list_zerotermux_capabilities" -> {
                    val cat = args.optString("category", "all").trim()
                    "→ list $cat"
                }
                "get_zerotermux_config" -> {
                    val group = args.optString("group", "").trim()
                    if (group.isEmpty()) "→ read config" else "→ read $group"
                }
                "get_zerotermux_left_menu" -> "→ read left menu xml"
                "update_zerotermux_left_menu" -> {
                    val pkg = args.optString("create_menu_package", args.optString("create_tab_name", "")).trim()
                    when {
                        pkg.isNotEmpty() -> "→ Menu 菜单: $pkg"
                        args.optString("append_group_xml", "").isNotBlank() -> "→ edit AI menu package"
                        args.optString("xml_content", "").isNotBlank() -> "→ replace AI menu xml"
                        else -> "→ update Menu 菜单"
                    }
                }
                "list_zerotermux_pkg_sources" -> "→ list APT mirrors"
                "switch_zerotermux_pkg_source" -> {
                    val id = args.optString("source_id", "").trim()
                    if (id.isEmpty()) "→ switch APT source" else "→ switch to $id"
                }
                "list_zerotermux_containers" -> "→ list containers"
                "create_zerotermux_container" -> {
                    val name = args.optString("container_name", args.optString("name", "")).trim()
                    if (name.isEmpty()) "→ create container" else "→ create $name"
                }
                "switch_zerotermux_container" -> {
                    val id = args.optString("container_id", args.optString("container_name", "")).trim()
                    if (id.isEmpty()) "→ switch container" else "→ switch to $id"
                }
                "delete_zerotermux_container" -> {
                    val id = args.optString("container_id", args.optString("container_name", "")).trim()
                    if (id.isEmpty()) "→ delete container" else "→ delete $id"
                }
                "list_zerotermux_command_defs" -> "→ list command defs"
                "add_zerotermux_command_def" -> {
                    val name = args.optString("name", "").trim()
                    if (name.isEmpty()) "→ add command def" else "→ add $name"
                }
                "update_zerotermux_command_def" -> "→ update command def"
                "delete_zerotermux_command_def" -> "→ delete command def"
                "run_zerotermux_command_def" -> "→ run command def"
                "list_directory" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) "→ list ~" else "→ list $path"
                }
                "read_file" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) "→ read file" else "→ read $path"
                }
                "write_file" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) "→ write file" else "→ write $path"
                }
                "create_file" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) "→ create file" else "→ create $path"
                }
                "mkdir" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) "→ mkdir" else "→ mkdir $path"
                }
                "delete_path" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) "→ delete" else "→ delete $path"
                }
                "stat_path" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) "→ stat" else "→ stat $path"
                }
                "list_agent_skills" -> "→ list ~/.skill"
                "save_agent_skill" -> {
                    val id = args.optString("skill_id", "").trim()
                    if (id.isEmpty()) "→ save skill" else "→ save skill $id"
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildWorkingMessages(
        history: List<ZtAgentAiChatClient.ChatMessage>
    ): MutableList<ZtAgentAiChatClient.ChatMessage> {
        val config = ZtAgentAiConfigHelper.loadActiveConfig()
        val list = mutableListOf<ZtAgentAiChatClient.ChatMessage>()
        list.add(
            ZtAgentAiChatClient.ChatMessage(
                role = ROLE_SYSTEM,
                content = ZtAgentAiConfigHelper.resolveSystemPrompt(
                    config.systemPrompt,
                    terminalEnabled,
                    ztControlEnabled,
                    filesystemEnabled
                )
            )
        )

        val historyMessages = history.filter { it.role == ROLE_USER || it.role == ROLE_ASSISTANT }
        val limited = if (terminalEnabled) {
            historyMessages.takeLast(TERMINAL_HISTORY_LIMIT)
        } else {
            historyMessages
        }

        if (terminalEnabled && limited.isNotEmpty()) {
            list.add(
                ZtAgentAiChatClient.ChatMessage(
                    role = ROLE_SYSTEM,
                    content = ZtAiStrings.terminalHistoryNotice()
                )
            )
            val lastUserIndex = limited.indexOfLast { it.role == ROLE_USER }
            limited.forEachIndexed { index, message ->
                if (message.role == ROLE_USER) {
                    val snapshot = if (index == lastUserIndex) {
                        ZtAgentAiTerminalExecutor.captureSnapshot(3000)
                    } else {
                        message.terminalSnapshot
                    }
                    list.add(
                        ZtAgentAiChatClient.ChatMessage(
                            role = ROLE_USER,
                            content = ZtAgentAiChatStore.contentWithSnapshot(
                                message.content.orEmpty(),
                                snapshot
                            )
                        )
                    )
                } else {
                    list.add(message)
                }
            }
        } else {
            list.addAll(limited)
        }

        return list
    }

    private fun post(block: () -> Unit) {
        UUtils.getHandler().post { block() }
    }

    companion object {
        private const val TAG = "ZtAgentAiAgentRunner"
        private const val TERMINAL_HISTORY_LIMIT = 6
        /** 连续被循环守卫硬阻断达到该次数后，整个 run 提前收尾。 */
        private const val MAX_CRITICAL_BLOCKS = 2
        /**
         * 循环守卫的收尾提示。接入建议：正式发版前把它搬进 strings.xml
         * （如 zt_agent_ai_loop_blocked），再用 ZtLocaleStrings.getString 取，保持多语言一致。
         */
        private const val LOOP_BLOCKED_MESSAGE =
            "Agent stopped: the loop guard detected repeated tool calls without progress. " +
                "Rephrase the task, or raise the tool-round limit / turn off the loop guard in Agent settings."
        private val SNAPSHOT_PREFIX: String
            get() = ZtAiStrings.terminalSnapshotPrefix()
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val ROLE_TOOL = "tool"
    }
}
