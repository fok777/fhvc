package com.termux.zerocore.ai.agent

import com.termux.R
import com.termux.zerocore.ai.config.ZtAiConfigExecutor
import com.termux.zerocore.utils.ZtLocaleStrings
import org.json.JSONObject

object ZtAgentAiToolExecutor {

    private val configToolNames = setOf(
        "list_zerotermux_capabilities",
        "get_zerotermux_config",
        "set_zerotermux_config",
        "open_zerotermux_page",
        "run_zerotermux_zt",
        "get_zerotermux_left_menu",
        "update_zerotermux_left_menu",
        "list_zerotermux_pkg_sources",
        "switch_zerotermux_pkg_source",
        "reset_zerotermux_beautify",
        "list_zerotermux_containers",
        "create_zerotermux_container",
        "switch_zerotermux_container",
        "delete_zerotermux_container",
        "list_zerotermux_command_defs",
        "add_zerotermux_command_def",
        "update_zerotermux_command_def",
        "delete_zerotermux_command_def",
        "run_zerotermux_command_def"
    )

    fun execute(
        toolCall: ZtAgentAiChatClient.ToolCall,
        terminalEnabled: Boolean,
        ztControlEnabled: Boolean,
        filesystemEnabled: Boolean = false,
        /** 子智能体执行器，仅 `dispatch_subagent` 需要；主会话传入，子会话传 null 防递归。 */
        subAgentRunner: ZtSubAgentRunner? = null
    ): String {
        // 新增：MCP 工具
        if (ZtMcpToolBridge.isMcpTool(toolCall.name)) {
            if (!ZtMcpServerStore.isEnabled()) {
                return "Error: MCP is disabled. Enable it in Agent Hub → MCP servers."
            }
            return ZtMcpToolBridge.execute(toolCall.name, toolCall.arguments)
        }
        // 新增：记忆 / 技能按需加载
        if (toolCall.name in ZtAgentAuxTools.toolNames) {
            if (toolCall.name in ZtAgentAuxTools.memoryToolNames && !ZtMemoryStore.isEnabled()) {
                return "Error: memory is disabled. Enable it in Agent Hub → Memory."
            }
            return ZtAgentAuxTools.execute(toolCall)
        }
        // 新增：子智能体
        if (toolCall.name == ZtSubAgentRunner.TOOL_NAME) {
            val runner = subAgentRunner
                ?: return "Error: sub-agents are not available in this context."
            if (!ZtAgentAiConfigHelper.isSubAgentEnabled()) {
                return "Error: sub-agents are disabled. Enable them in Agent Hub → Sub-agents."
            }
            return ZtSubAgentRunner.format(runner.run(ZtSubAgentRunner.parse(toolCall.arguments)))
        }
        if (toolCall.name in configToolNames) {
            if (!ztControlEnabled) {
                return disabledMessage(com.termux.R.string.zt_agent_ai_zt_control_tool_disabled)
            }
            return ZtAiConfigExecutor.execute(toolCall)
        }
        if (toolCall.name in ZtAgentAiFilesystemExecutor.toolNames) {
            if (!filesystemEnabled) {
                return disabledMessage(
                    if (terminalEnabled) {
                        com.termux.R.string.zt_agent_ai_filesystem_tool_disabled
                    } else {
                        com.termux.R.string.zt_agent_ai_filesystem_tool_disabled_no_terminal
                    }
                )
            }
            return ZtAgentAiFilesystemExecutor.execute(toolCall)
        }
        if (toolCall.name in ZtAgentAiSkillTools.toolNames) {
            return ZtAgentAiSkillExecutor.execute(toolCall)
        }
        return when (toolCall.name) {
            "read_terminal", "send_terminal_command", "send_terminal_key" -> {
                if (!terminalEnabled) {
                    return disabledMessage(com.termux.R.string.zt_agent_ai_terminal_tool_disabled)
                }
                ZtAgentAiTerminalExecutor.execute(toolCall)
            }
            "run_zt_command" -> {
                if (!ztControlEnabled) {
                    return disabledMessage(com.termux.R.string.zt_agent_ai_zt_control_tool_disabled)
                }
                if (!terminalEnabled) {
                    return disabledMessage(com.termux.R.string.zt_agent_ai_zt_requires_terminal)
                }
                runZtCommand(toolCall)
            }
            else -> "Error: unknown tool `${toolCall.name}`"
        }
    }

    private fun disabledMessage(@androidx.annotation.StringRes messageRes: Int): String {
        return ZtLocaleStrings.getString(messageRes)
    }

    fun statusLabel(toolName: String): String {
        // 新增工具的状态文案用常量而非 string resource，避免接入时还要补 strings.xml 才能编译。
        if (ZtMcpToolBridge.isMcpTool(toolName)) {
            return ZtNewToolLabels.MCP
        }
        if (toolName in ZtAgentAuxTools.toolNames) {
            return when (toolName) {
                ZtAgentAuxTools.SKILL_READ -> ZtNewToolLabels.READ_SKILL
                ZtAgentAuxTools.MEMORY_SEARCH -> ZtNewToolLabels.MEMORY_SEARCH
                ZtAgentAuxTools.MEMORY_APPEND -> ZtNewToolLabels.MEMORY_APPEND
                else -> ZtNewToolLabels.MEMORY
            }
        }
        if (toolName == ZtSubAgentRunner.TOOL_NAME) {
            return ZtNewToolLabels.SUBAGENT
        }
        if (toolName in configToolNames) {
            return ZtAiConfigExecutor.statusLabel(toolName)
        }
        if (toolName in ZtAgentAiFilesystemExecutor.toolNames) {
            return ZtAgentAiFilesystemExecutor.statusLabel(toolName)
        }
        if (toolName in ZtAgentAiSkillTools.toolNames) {
            return ZtAgentAiSkillExecutor.statusLabel(toolName)
        }
        return when (toolName) {
            "read_terminal" -> ZtLocaleStrings.getString(R.string.zt_agent_ai_tool_read_terminal)
            "send_terminal_command" -> ZtLocaleStrings.getString(R.string.zt_agent_ai_tool_send_command)
            "send_terminal_key" -> ZtLocaleStrings.getString(R.string.zt_agent_ai_tool_send_key)
            "run_zt_command" -> ZtLocaleStrings.getString(R.string.zt_agent_ai_tool_run_command)
            else -> ZtLocaleStrings.getString(R.string.zt_agent_ai_tool_running)
        }
    }

    private fun runZtCommand(toolCall: ZtAgentAiChatClient.ToolCall): String {
        val args = JSONObject(toolCall.arguments.ifBlank { "{}" })
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) {
            return "Error: command is required. Run help first. Examples: help, openpage zt_settings, openleft, toast hello"
        }
        android.util.Log.i("ZT_CMD", "run_zt_command via terminal: zt $command")
        return ZtAgentAiTerminalExecutor.runZtCommand(command)
    }
}
