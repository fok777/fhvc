package com.termux.zerocore.ai.agent

import com.example.xh_lib.utils.SaveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.termux.zerocore.bean.ZTUserBean
import com.termux.zerocore.ftp.utils.UserSetManage
import com.termux.zerocore.utils.ZtLocaleStrings

object ZtAgentAiConfigHelper {

    const val DEFAULT_TOOL_ROUNDS = 40
    private val TOOL_ROUND_OPTIONS = intArrayOf(10, 20, 40, 60, 80, 100)

    // ─── 死循环守卫 / 上下文预算（存通用 KV，避免改动 ZTUserBean 结构） ───────
    private const val KEY_LOOP_GUARD = "zt_agent_loop_guard"
    private const val KEY_BUDGET_CHARS = "zt_agent_budget_chars"
    private const val KEY_TOOL_RESULT_CHARS = "zt_agent_tool_result_chars"
    private const val KEY_SUBAGENT = "zt_agent_subagent_enabled"
    private const val KEY_SUBAGENT_ROUNDS = "zt_agent_subagent_rounds"

    val BUDGET_CHAR_OPTIONS = intArrayOf(60_000, 120_000, 200_000, 320_000)
    val TOOL_RESULT_CHAR_OPTIONS = intArrayOf(4_000, 8_000, 16_000, 32_000)

    private val gson = Gson()

    data class ProviderConfig(
        var apiUrl: String = "",
        var apiKey: String = "",
        var model: String = ""
    )

    data class ActiveConfig(
        val provider: String,
        val apiUrl: String,
        val apiKey: String,
        val model: String,
        val systemPrompt: String,
        val apiFormat: String
    )

    fun activeProvider(): String {
        val bean = UserSetManage.get().getZTUserBean()
        val provider = bean.agentAiActiveProvider
        return if (provider.isNullOrBlank()) ZtAgentAiProvider.DEEPSEEK else provider
    }

    fun loadActiveConfig(): ActiveConfig {
        val bean = UserSetManage.get().getZTUserBean()
        val provider = activeProvider()
        val defaults = ZtAgentAiProvider.defaults(provider)
        return ActiveConfig(
            provider = provider,
            apiUrl = bean.agentAiApiUrl?.takeIf { it.isNotBlank() } ?: defaults.apiUrl,
            apiKey = bean.agentAiApiKey.orEmpty(),
            model = bean.agentAiModel?.takeIf { it.isNotBlank() } ?: defaults.model,
            systemPrompt = bean.agentAiSystemPrompt.orEmpty(),
            apiFormat = defaults.apiFormat
        )
    }

    fun loadProviderConfig(provider: String): ProviderConfig {
        val cache = readCache()
        val cached = cache[provider]
        val defaults = ZtAgentAiProvider.defaults(provider)
        return ProviderConfig(
            apiUrl = cached?.apiUrl?.takeIf { it.isNotBlank() } ?: defaults.apiUrl,
            apiKey = cached?.apiKey.orEmpty(),
            model = cached?.model?.takeIf { it.isNotBlank() } ?: defaults.model
        )
    }

    fun switchProvider(newProvider: String) {
        val bean = UserSetManage.get().getZTUserBean()
        val currentProvider = activeProvider()
        if (currentProvider != newProvider) {
            saveCurrentToCache(bean, currentProvider)
        }
        bean.agentAiActiveProvider = newProvider
        applyCachedProvider(bean, newProvider)
        UserSetManage.get().setZTUserBean(bean)
    }

    fun saveActiveFields(apiUrl: String, apiKey: String, model: String, systemPrompt: String) {
        val bean = UserSetManage.get().getZTUserBean()
        bean.agentAiApiUrl = apiUrl.trim()
        bean.agentAiApiKey = apiKey.trim()
        bean.agentAiModel = model.trim()
        bean.agentAiSystemPrompt = systemPrompt.trim()
        saveCurrentToCache(bean, activeProvider())
        UserSetManage.get().setZTUserBean(bean)
    }

    fun isTerminalEnabled(): Boolean {
        return UserSetManage.get().getZTUserBean().isAgentAiTerminalEnabled
    }

    fun saveTerminalEnabled(enabled: Boolean) {
        val bean = UserSetManage.get().getZTUserBean()
        bean.setAgentAiTerminalEnabled(enabled)
        UserSetManage.get().setZTUserBean(bean)
    }

    fun isZtControlEnabled(): Boolean {
        return UserSetManage.get().getZTUserBean().isAgentAiZtControlEnabled
    }

    fun saveZtControlEnabled(enabled: Boolean) {
        val bean = UserSetManage.get().getZTUserBean()
        bean.setAgentAiZtControlEnabled(enabled)
        UserSetManage.get().setZTUserBean(bean)
    }

    fun isFilesystemEnabled(): Boolean {
        return UserSetManage.get().getZTUserBean().isAgentAiFilesystemEnabled
    }

    fun saveFilesystemEnabled(enabled: Boolean) {
        val bean = UserSetManage.get().getZTUserBean()
        bean.setAgentAiFilesystemEnabled(enabled)
        UserSetManage.get().setZTUserBean(bean)
    }

    fun maxToolRounds(): Int {
        return normalizeToolRounds(UserSetManage.get().getZTUserBean().agentAiMaxToolRounds)
    }

    fun saveMaxToolRounds(rounds: Int) {
        val bean = UserSetManage.get().getZTUserBean()
        bean.agentAiMaxToolRounds = normalizeToolRounds(rounds)
        UserSetManage.get().setZTUserBean(bean)
    }

    fun toolRoundOptions(): IntArray = TOOL_ROUND_OPTIONS

    private fun normalizeToolRounds(value: Int): Int {
        return if (value in TOOL_ROUND_OPTIONS) value else DEFAULT_TOOL_ROUNDS
    }

    fun resolveSystemPrompt(
        rawPrompt: String,
        terminalEnabled: Boolean,
        ztControlEnabled: Boolean = isZtControlEnabled(),
        filesystemEnabled: Boolean = isFilesystemEnabled()
    ): String {
        val base = rawPrompt.trim().ifBlank {
            ZtLocaleStrings.getString(com.termux.R.string.zt_agent_ai_default_system_prompt)
        }
        val parts = mutableListOf(
            ZtLocaleStrings.getString(com.termux.R.string.zt_agent_ai_locale_reply_prompt),
            base,
            buildPermissionsStatusPrompt(terminalEnabled, ztControlEnabled, filesystemEnabled),
            ZtLocaleStrings.getString(com.termux.R.string.zt_agent_ai_permission_guard_prompt)
        )
        if (!terminalEnabled && !ztControlEnabled && !filesystemEnabled) {
            parts.add(
                ZtLocaleStrings.getString(
                    com.termux.R.string.zt_agent_ai_all_tools_disabled_prompt
                )
            )
        }
        if (terminalEnabled) {
            parts.add(
                ZtLocaleStrings.getString(
                    com.termux.R.string.zt_agent_ai_terminal_system_prompt
                )
            )
        } else {
            parts.add(
                ZtLocaleStrings.getString(
                    com.termux.R.string.zt_agent_ai_terminal_disabled_prompt
                )
            )
        }
        if (ztControlEnabled) {
            parts.add(
                ZtLocaleStrings.getString(
                    com.termux.R.string.zt_agent_ai_zt_control_system_prompt
                )
            )
        } else {
            parts.add(
                ZtLocaleStrings.getString(
                    com.termux.R.string.zt_agent_ai_zt_control_disabled_prompt
                )
            )
        }
        if (filesystemEnabled) {
            parts.add(
                ZtLocaleStrings.getString(
                    com.termux.R.string.zt_agent_ai_filesystem_system_prompt
                )
            )
        } else {
            parts.add(
                ZtLocaleStrings.getString(
                    if (terminalEnabled) {
                        com.termux.R.string.zt_agent_ai_filesystem_disabled_prompt
                    } else {
                        com.termux.R.string.zt_agent_ai_filesystem_disabled_no_terminal
                    }
                )
            )
        }
        if (terminalEnabled || ztControlEnabled || filesystemEnabled) {
            parts.add(
                ZtLocaleStrings.getString(
                    com.termux.R.string.zt_agent_ai_tool_usage_prompt
                )
            )
        }
        parts.add(
            ZtLocaleStrings.getString(
                com.termux.R.string.zt_agent_ai_gui_compile_prompt
            )
        )
        // 人格：性格 / 边界 / 世界书
        val persona = ZtPersonaStore.activePrompt()
        if (persona.isNotBlank()) parts.add(persona)
        // 新增：长期记忆 + 记忆工具说明
        val memory = ZtMemoryStore.globalPrompt()
        if (memory.isNotBlank()) parts.add(memory)
        val memoryTools = ZtMemoryStore.toolsPrompt()
        if (memoryTools.isNotBlank()) parts.add(memoryTools)
        parts.add(ZtAgentAiSkillHelper.skillsCapabilityPrompt())
        val skillsBlock = ZtAgentAiSkillHelper.buildSkillsPrompt(ZtAgentAiSkillHelper.enabledSkillIds())
        if (skillsBlock.isNotBlank()) {
            parts.add(skillsBlock)
        }
        return parts.joinToString("\n\n")
    }

    private fun buildPermissionsStatusPrompt(
        terminalEnabled: Boolean,
        ztControlEnabled: Boolean,
        filesystemEnabled: Boolean
    ): String {
        fun statusLine(nameRes: Int, enabled: Boolean): String {
            val name = ZtLocaleStrings.getString(nameRes)
            val state = ZtLocaleStrings.getString(
                if (enabled) {
                    com.termux.R.string.zt_agent_ai_perm_enabled
                } else {
                    com.termux.R.string.zt_agent_ai_perm_disabled
                }
            )
            return "- $name：$state"
        }
        return buildString {
            appendLine(ZtLocaleStrings.getString(com.termux.R.string.zt_agent_ai_permissions_status_header))
            appendLine(statusLine(com.termux.R.string.zt_agent_ai_terminal_enabled, terminalEnabled))
            appendLine(statusLine(com.termux.R.string.zt_agent_ai_zt_control_enabled, ztControlEnabled))
            append(statusLine(com.termux.R.string.zt_agent_ai_filesystem_enabled, filesystemEnabled))
        }.trim()
    }

    fun isAgentToolsEnabled(): Boolean {
        return isTerminalEnabled() || isZtControlEnabled() || isFilesystemEnabled()
    }

    /** Skill 工具始终可用；无其它权限时仍可走 Agent 循环保存/列举 Skill。 */
    fun shouldUseAgentRunner(): Boolean {
        return isAgentToolsEnabled() || hasSkillTools()
    }

    fun hasSkillTools(): Boolean = true

    // ─── 死循环守卫 ────────────────────────────────────────────────────────

    /** 默认开启：识别重复工具调用 / 幻觉工具名 / 轮询无进展，阻断或告警。 */
    fun isLoopGuardEnabled(): Boolean {
        return readStringPref(KEY_LOOP_GUARD)?.let { it == "1" } ?: true
    }

    fun saveLoopGuardEnabled(enabled: Boolean) {
        SaveData.saveStringOther(KEY_LOOP_GUARD, if (enabled) "1" else "0")
    }

    /**
     * 按工具轮数上限等比缩放阈值，保证 40 轮和 100 轮设置下守卫都既不过敏也不失效。
     */
    fun loopConfig(): ZtLoopConfig {
        val rounds = maxToolRounds()
        val scale = (rounds / DEFAULT_TOOL_ROUNDS.toFloat()).coerceIn(0.5f, 3f)
        return ZtLoopConfig(
            historySize = 30,
            warningThreshold = (6 * scale).toInt().coerceAtLeast(3),
            unknownToolThreshold = 3,
            criticalThreshold = (10 * scale).toInt().coerceAtLeast(5),
            globalCircuitBreakerThreshold = (16 * scale).toInt().coerceAtLeast(9)
        )
    }

    // ─── 上下文预算 ────────────────────────────────────────────────────────

    fun contextBudget(): ZtAgentContextBudget.BudgetConfig {
        val maxTool = readIntPref(KEY_TOOL_RESULT_CHARS) ?: 8_000
        val toolChars = if (maxTool in TOOL_RESULT_CHAR_OPTIONS) maxTool else 8_000

        // 用户在设置里手选过预算就尊重它；否则按当前模型的真实上下文窗口推导，
        // 留出 1/4 给回复与工具回灌，避免 128K 的小模型被 120K 字符的请求撑爆。
        val manual = readIntPref(KEY_BUDGET_CHARS)
        val maxPrompt = if (manual != null && manual in BUDGET_CHAR_OPTIONS) {
            manual
        } else {
            deriveBudgetFromModel()
        }

        return ZtAgentContextBudget.BudgetConfig(
            maxPromptChars = maxPrompt,
            maxToolResultChars = toolChars,
            keepTailChars = toolChars * 2 / 5
        )
    }

    /**
     * 按当前模型的上下文窗口推导字符预算。
     * 中文按 1 字 ≈ 1 token、英文按 4 字符 ≈ 1 token 的粗估，取 75% 作为请求上限。
     */
    private fun deriveBudgetFromModel(): Int {
        val context = activeModel().contextLength
        // 混合语境下 token→字符 的保守换算：约 2.5 字符/token。
        val chars = (context * 0.75 * 2.5).toInt()
        return chars.coerceIn(40_000, 320_000)
    }

    fun saveContextBudget(maxPromptChars: Int, maxToolResultChars: Int) {
        SaveData.saveStringOther(KEY_BUDGET_CHARS, maxPromptChars.toString())
        SaveData.saveStringOther(KEY_TOOL_RESULT_CHARS, maxToolResultChars.toString())
    }

    // ─── 子智能体 ────────────────────────────────────────────────────────────

    /** 默认关闭：子智能体会额外消耗 token，且需要工具权限才有用。 */
    fun isSubAgentEnabled(): Boolean = readStringPref(KEY_SUBAGENT)?.let { it == "1" } ?: false

    fun saveSubAgentEnabled(enabled: Boolean) {
        SaveData.saveStringOther(KEY_SUBAGENT, if (enabled) "1" else "0")
    }

    /** 子智能体默认轮次（1..ZtSubAgentRunner.MAX_ROUNDS）。 */
    fun subAgentRounds(): Int = readIntPref(KEY_SUBAGENT_ROUNDS)
        ?.coerceIn(1, ZtSubAgentRunner.MAX_ROUNDS) ?: ZtSubAgentRunner.DEFAULT_ROUNDS

    fun saveSubAgentRounds(rounds: Int) {
        SaveData.saveStringOther(
            KEY_SUBAGENT_ROUNDS,
            rounds.coerceIn(1, ZtSubAgentRunner.MAX_ROUNDS).toString()
        )
    }

    // ─── 模型目录 ────────────────────────────────────────────────────────────

    /** 把当前手填/选中的模型 ID 解析成能力描述，供预算与提示词使用。 */
    fun activeModel(): ZtModelInfo {
        val id = loadActiveConfig().model
        return if (id.isBlank()) ZtModelInfo.unknown("") else ZtModelRegistry.resolve(id)
    }

    /** 清掉模型列表缓存（换 key、换端点后调用）。 */
    fun invalidateModelCache() {
        val config = loadActiveConfig()
        ZtModelCatalog.invalidate(config.apiUrl, config.apiKey)
    }

    private fun readStringPref(key: String): String? {
        val raw = SaveData.getStringOther(key)
        if (raw.isNullOrBlank() || raw == "def") return null
        return raw.trim()
    }

    private fun readIntPref(key: String): Int? = readStringPref(key)?.toIntOrNull()

    fun isConfigured(): Boolean {
        val config = loadActiveConfig()
        return config.apiUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()
    }

    private fun applyCachedProvider(bean: ZTUserBean, provider: String) {
        val config = loadProviderConfig(provider)
        bean.agentAiApiUrl = config.apiUrl
        bean.agentAiApiKey = config.apiKey
        bean.agentAiModel = config.model
    }

    private fun saveCurrentToCache(bean: ZTUserBean, provider: String) {
        if (provider.isBlank()) return
        val cache = readCache()
        cache[provider] = ProviderConfig(
            apiUrl = bean.agentAiApiUrl.orEmpty(),
            apiKey = bean.agentAiApiKey.orEmpty(),
            model = bean.agentAiModel.orEmpty()
        )
        bean.agentAiProviderCacheJson = gson.toJson(cache)
    }

    private fun readCache(): MutableMap<String, ProviderConfig> {
        val json = UserSetManage.get().getZTUserBean().agentAiProviderCacheJson
        if (json.isNullOrBlank()) return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, ProviderConfig>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }
}
