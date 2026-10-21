package com.termux.zerocore.ai.agent

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import com.example.xh_lib.utils.UUtils
import com.termux.R
import com.termux.zerocore.settings.BaseTitleActivity

/**
 * AI 智能体总控页。
 *
 * 子页面（MCP 服务器列表、人格编辑、记忆查看）一律用代码搭 UI，不额外新增 layout 文件。
 */
class ZtAgentHubActivity : BaseTitleActivity() {

    private val statusView by lazy { findViewById<TextView>(R.id.hub_status) }
    private val modelCard by lazy { findViewById<CardView>(R.id.hub_card_model) }
    private val modelSummary by lazy { findViewById<TextView>(R.id.hub_model_summary) }
    private val mcpSwitch by lazy { findViewById<SwitchCompat>(R.id.hub_mcp_switch) }
    private val mcpSummary by lazy { findViewById<TextView>(R.id.hub_mcp_summary) }
    private val mcpManage by lazy { findViewById<TextView>(R.id.hub_mcp_manage) }
    private val personaCard by lazy { findViewById<CardView>(R.id.hub_card_persona) }
    private val personaSummary by lazy { findViewById<TextView>(R.id.hub_persona_summary) }
    private val memoryCard by lazy { findViewById<CardView>(R.id.hub_card_memory) }
    private val memorySwitch by lazy { findViewById<SwitchCompat>(R.id.hub_memory_switch) }
    private val memorySummary by lazy { findViewById<TextView>(R.id.hub_memory_summary) }
    private val skillsCard by lazy { findViewById<CardView>(R.id.hub_card_skills) }
    private val skillsSummary by lazy { findViewById<TextView>(R.id.hub_skills_summary) }
    private val subAgentSwitch by lazy { findViewById<SwitchCompat>(R.id.hub_subagent_switch) }
    private val subAgentSummary by lazy { findViewById<TextView>(R.id.hub_subagent_summary) }
    private val roundsRow by lazy { findViewById<LinearLayout>(R.id.hub_subagent_rounds_row) }
    private val roundsCards by lazy {
        listOf(
            4 to findViewById<CardView>(R.id.hub_rounds_4),
            8 to findViewById<CardView>(R.id.hub_rounds_8),
            16 to findViewById<CardView>(R.id.hub_rounds_16)
        )
    }
    private val guardSwitch by lazy { findViewById<SwitchCompat>(R.id.hub_guard_switch) }
    private val guardSummary by lazy { findViewById<TextView>(R.id.hub_guard_summary) }
    private val modelGroupCard by lazy { findViewById<CardView>(R.id.hub_card_model_group) }
    private val modelGroupSummary by lazy { findViewById<TextView>(R.id.hub_model_group_summary) }
    private val omniCard by lazy { findViewById<CardView>(R.id.hub_card_omniroute) }
    private val omniSummary by lazy { findViewById<TextView>(R.id.hub_omniroute_summary) }
    private val endpointCard by lazy { findViewById<CardView>(R.id.hub_card_endpoint) }
    private val endpointSummary by lazy { findViewById<TextView>(R.id.hub_endpoint_summary) }
    private val agingSwitch by lazy { findViewById<SwitchCompat>(R.id.hub_aging_switch) }
    private val agingSummary by lazy { findViewById<TextView>(R.id.hub_aging_summary) }
    private val agingStrengthRow by lazy { findViewById<LinearLayout>(R.id.hub_aging_strength_row) }
    private val agingLevelCards by lazy {
        listOf(
            0 to findViewById<TextView>(R.id.hub_aging_mild),
            1 to findViewById<TextView>(R.id.hub_aging_normal),
            2 to findViewById<TextView>(R.id.hub_aging_strong)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zt_agent_hub)
        setBaseTitle(UUtils.getString(R.string.zt_agent_ai_settings_title))

        // 记忆与人格要读写 filesDir，先喂一个 Context。
        ZtMemoryStore.init(this)
        ZtModelCatalog.init(this)

        bindModel()
        bindMcp()
        bindPersona()
        bindMemory()
        bindSkills()
        bindSubAgent()
        bindGuard()
        bindEndpoint()
        bindAging()
        bindOmniRoute()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    // ─── 模型与服务商 ────────────────────────────────────────────────────────

    private fun bindModel() {
        modelCard.setOnClickListener {
            startActivity(Intent(this, ZtAgentAiSettingsActivity::class.java))
        }
    }

    private fun refreshModel() {
        val config = ZtAgentAiConfigHelper.loadActiveConfig()
        modelSummary.text = if (ZtAgentAiConfigHelper.isConfigured()) {
            val model = ZtAgentAiConfigHelper.activeModel()
            val ctx = if (model.contextLength >= 1_000_000) {
                "${model.contextLength / 1_000_000}M"
            } else {
                "${model.contextLength / 1_000}K"
            }
            "${config.provider} · ${config.model} · ${ctx}"
        } else {
            "未配置：点此填写 API 地址与密钥"
        }
    }

    // ─── MCP ────────────────────────────────────────────────────────────────

    private fun bindMcp() {
        mcpSwitch.setOnCheckedChangeListener { _, checked ->
            ZtMcpServerStore.saveEnabled(checked)
            if (checked) ZtMcpToolBridge.invalidate()
            refreshMcp()
        }
        mcpManage.setOnClickListener { showMcpServersDialog() }
    }

    private fun refreshMcp() {
        val enabled = ZtMcpServerStore.isEnabled()
        mcpSwitch.isChecked = enabled
        mcpManage.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        val servers = ZtMcpServerStore.list()
        val active = servers.count { it.enabled }
        val tools = ZtMcpToolBridge.cachedCount()
        mcpSummary.text = when {
            !enabled -> "已关闭 · 接入外部 MCP 工具（联网搜索、数据库等）"
            servers.isEmpty() -> "已开启 · 还没有服务器，点「管理服务器」添加"
            tools > 0 -> "已开启 · $active/${servers.size} 个服务器 · $tools 个工具"
            else -> "已开启 · $active/${servers.size} 个服务器 · 首次调用时发现工具"
        }
    }

    // ─── 人格 ────────────────────────────────────────────────────────────────

    private fun bindPersona() {
        personaCard.setOnClickListener { showPersonaDialog() }
    }

    private fun refreshPersona() {
        val persona = ZtPersonaStore.active()
        personaSummary.text = persona?.let { "${it.avatar} ${it.name}" }
            ?: "未启用 · 给 agent 一个稳定的性格与边界"
    }

    // ─── 记忆 ────────────────────────────────────────────────────────────────

    private fun bindMemory() {
        memorySwitch.setOnCheckedChangeListener { _, checked ->
            ZtMemoryStore.saveEnabled(checked)
            refreshMemory()
        }
        memoryCard.setOnClickListener { showMemoryDialog() }
    }

    private fun refreshMemory() {
        memorySwitch.isChecked = ZtMemoryStore.isEnabled()
        val text = ZtMemoryStore.readGlobal()
        val count = text.lineSequence().count { it.trimStart().startsWith("-") }
        memorySummary.text = if (!ZtMemoryStore.isEnabled()) {
            "已关闭 · 跨会话记住你的偏好与环境"
        } else if (count == 0) {
            "已开启 · 还没有记忆，agent 会用 memory_append 自己记"
        } else {
            "已开启 · $count 条记忆 · 点击查看/编辑"
        }
    }

    // ─── 技能 ────────────────────────────────────────────────────────────────

    private fun bindSkills() {
        skillsCard.setOnClickListener {
            startActivity(Intent(this, ZtAgentAiSkillsActivity::class.java))
        }
    }

    private fun refreshSkills() {
        val all = runCatching { ZtAgentAiSkillHelper.listAvailableSkills() }.getOrDefault(emptyList())
        val enabled = runCatching { ZtAgentAiSkillHelper.enabledSkillIds() }.getOrDefault(emptySet())
        skillsSummary.text = if (all.isEmpty()) {
            "~/.skill 下还没有技能"
        } else {
            "${enabled.size}/${all.size} 个已启用 · 可在对话中用 read_agent_skill 按需加载"
        }
    }

    // ─── 子智能体 ────────────────────────────────────────────────────────────

    private fun bindSubAgent() {
        subAgentSwitch.setOnCheckedChangeListener { _, checked ->
            ZtAgentAiConfigHelper.saveSubAgentEnabled(checked)
            refreshSubAgent()
        }
        roundsCards.forEach { (rounds, card) ->
            card.setOnClickListener {
                ZtAgentAiConfigHelper.saveSubAgentRounds(rounds)
                refreshSubAgent()
            }
        }
    }

    private fun refreshSubAgent() {
        val enabled = ZtAgentAiConfigHelper.isSubAgentEnabled()
        subAgentSwitch.isChecked = enabled
        roundsRow.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        val active = ZtAgentAiConfigHelper.subAgentRounds()
        val normal = runCatching { getColor(R.color.color_55000000) }.getOrDefault(Color.DKGRAY)
        val selected = runCatching { getColor(R.color.color_5548baf3) }.getOrDefault(Color.CYAN)
        roundsCards.forEach { (rounds, card) ->
            card.setCardBackgroundColor(if (rounds == active) selected else normal)
        }
        subAgentSummary.text = if (enabled) {
            "已开启 · 每个子任务最多 $active 轮工具调用（独立上下文）"
        } else {
            "已关闭 · 把独立子任务派出去，隔离上下文"
        }
    }

    // ─── 死循环守卫 ──────────────────────────────────────────────────────────

    private fun bindGuard() {
        guardSwitch.setOnCheckedChangeListener { _, checked ->
            ZtAgentAiConfigHelper.saveLoopGuardEnabled(checked)
            refreshGuard()
        }
    }

    private fun refreshGuard() {
        guardSwitch.isChecked = ZtAgentAiConfigHelper.isLoopGuardEnabled()
        val budget = ZtAgentAiConfigHelper.contextBudget()
        guardSummary.text = if (guardSwitch.isChecked) {
            "已开启 · 上下文预算 ${budget.maxPromptChars / 1000}K 字符"
        } else {
            "已关闭 · 只靠工具轮数上限兜底，可能白烧 token"
        }
    }

    private fun refreshAll() {
        refreshModel()
        refreshMcp()
        refreshPersona()
        refreshMemory()
        refreshSkills()
        refreshSubAgent()
        refreshGuard()
        refreshEndpoint()
        refreshAging()
        refreshOmniRoute()
        val config = ZtAgentAiConfigHelper.loadActiveConfig()
        statusView.text = if (ZtAgentAiConfigHelper.isConfigured()) {
            "已配置 · ${config.provider} / ${config.model}"
        } else {
            "未配置 API，先点「模型与服务商」"
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 以下子页面全部用代码搭 UI
    // ═════════════════════════════════════════════════════════════════════════

    // ─── 模型组 ────────────────────────────────────────────────────────────

    private fun bindModelGroups() {
        modelGroupCard.setOnClickListener {
            startActivity(Intent(this, ZtModelGroupActivity::class.java))
        }
    }

    private fun refreshModelGroups() {
        val groups = ZtModelGroupStore.loadGroups()
        val active = ZtModelGroupStore.activeGroup()
        modelGroupSummary.text = when {
            groups.isEmpty() -> "未配置 · 组可容纳多个模型并自动 fallback"
            active != null -> "${active.name} · ${groups.size} 个组"
            else -> "${groups.size} 个组"
        }
    }

    // ─── OmniRoute 本地网关 ──────────────────────────────────────────────────

    private fun bindOmniRoute() {
        omniCard.setOnClickListener {
            startActivity(Intent(this, ZtGatewayActivity::class.java))
        }
    }

    private fun refreshOmniRoute() {
        val count = ZtLocalGateways.BUILTIN.size + ZtLocalGateways.DIRECT_PRESETS.size
        omniSummary.text = "OmniRoute · Zen Proxy · freellmpool 等 $count 个来源，含免密直连" 
    }

    // ─── 端点与限速 ──────────────────────────────────────────────────────────

    private fun bindEndpoint() {
        endpointCard.setOnClickListener { showEndpointDialog() }
    }

    private fun refreshEndpoint() {
        val profile = ZtAgentAiConfigHelper.endpointProfile()
        val auto = ZtEndpointProfiles.fromUrl(ZtAgentAiConfigHelper.loadActiveConfig().apiUrl)
        val manual = profile.rpm != auto.rpm || profile.maxConcurrency != auto.maxConcurrency
        val token = if (profile.tokenUnlimited) "token 不限" else "按 token 计费"
        endpointSummary.text = buildString {
            append(auto.id)
            append(" · ")
            append(profile.rpm)
            append(" 次/分钟 · 并发 ")
            append(profile.maxConcurrency)
            append(" · ")
            append(token)
            if (manual) append(" · 已手动覆盖")
        }
    }

    private fun showEndpointDialog() {
        val profile = ZtAgentAiConfigHelper.endpointProfile()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        fun label(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(0, dp(10), 0, dp(4))
            })
        }

        fun hint(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                setTextColor(runCatching { getColor(R.color.md_grey_500) }.getOrDefault(Color.GRAY))
                textSize = 11f
                setPadding(0, 0, 0, dp(4))
            })
        }

        fun field(initial: String, inputType: Int): EditText = EditText(this).apply {
            setText(initial)
            this.inputType = inputType
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        label("每分钟请求上限 (RPM)")
        hint("免费端点普遍很低。改小能避免 429，改大只会更快撞墙。")
        val rpmEdit = field(profile.rpm.toString(), InputType.TYPE_CLASS_NUMBER)

        label("最大并发")
        hint("免费端点通常是 1。调高不会更快，只会让请求排队或被拒。")
        val concEdit = field(profile.maxConcurrency.toString(), InputType.TYPE_CLASS_NUMBER)

        container.addView(rpmEdit)
        container.addView(concEdit)

        val unlimitedSwitch = SwitchCompat(this).apply {
            isChecked = profile.tokenUnlimited
            text = "token 不限量（免费档）"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        }
        hint("勾上后不会为了省 token 额外发请求；压缩类操作只在按 token 计费时才划算。")
        container.addView(unlimitedSwitch)

        val scroll = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("端点与限速")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setNeutralButton("自动识别") { _, _ ->
                ZtAgentAiConfigHelper.resetEndpointOverrides()
                refreshEndpoint()
            }
            .setPositiveButton("保存") { _, _ ->
                val rpm = rpmEdit.text.toString().toIntOrNull()
                val conc = concEdit.text.toString().toIntOrNull()
                ZtAgentAiConfigHelper.saveEndpointOverrides(rpm, conc, unlimitedSwitch.isChecked)
                refreshEndpoint()
            }
            .show()
    }

    // ─── 上下文老化 ──────────────────────────────────────────────────────────

    private fun bindAging() {
        agingSwitch.setOnCheckedChangeListener { _, checked ->
            ZtAgentAiConfigHelper.setContextAgingEnabled(checked)
            refreshAging()
        }
        agingLevelCards.forEach { (level, view) ->
            view.setOnClickListener {
                ZtAgentAiConfigHelper.saveAgingLevel(level)
                refreshAging()
            }
        }
    }

    private fun refreshAging() {
        val enabled = ZtAgentAiConfigHelper.isContextAgingEnabled()
        agingSwitch.isChecked = enabled
        agingStrengthRow.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE

        val active = ZtAgentAiConfigHelper.agingLevel()
        val normal = runCatching { getColor(R.color.color_55000000) }.getOrDefault(Color.DKGRAY)
        val selected = runCatching { getColor(R.color.color_5548baf3) }.getOrDefault(Color.CYAN)
        agingLevelCards.forEach { (level, view) ->
            view.setBackgroundColor(if (level == active) selected else normal)
        }

        agingSummary.text = if (!enabled) {
            "已关闭 · 长会话会完整重发历史，越聊越慢"
        } else {
            val cfg = ZtAgentAiConfigHelper.contextAging()
            "已开启 · 最近 ${cfg.freshTurns} 轮完整，更早折叠到 ${cfg.agedToolChars} 字符"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // ─── MCP 服务器列表 ──────────────────────────────────────────────────────

    private fun showMcpServersDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listLayout)

        val dialog = AlertDialog.Builder(this)
            .setTitle("MCP 服务器")
            .setView(container)
            .setPositiveButton("添加", null)
            .setNegativeButton("关闭", null)
            .create()

        fun render() {
            listLayout.removeAllViews()
            val servers = ZtMcpServerStore.list()
            if (servers.isEmpty()) {
                listLayout.addView(TextView(this).apply {
                    text = "还没有服务器。点「添加」填一个 Streamable HTTP 端点，\n" +
                        "例如 https://mcp.example.com/mcp"
                    textSize = 13f
                    setTextColor(Color.parseColor("#888888"))
                    setPadding(0, dp(12), 0, dp(12))
                })
                return
            }
            servers.forEach { server ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { showMcpEditDialog(server) { render() } }
                }
                row.addView(TextView(this).apply {
                    text = buildString {
                        append(if (server.enabled) "● " else "○ ")
                        append(server.name.ifBlank { server.id })
                        if (server.lastToolCount > 0) append("  ·  ${server.lastToolCount} 工具")
                    }
                    textSize = 14f
                    setTextColor(Color.WHITE)
                })
                row.addView(TextView(this).apply {
                    text = server.url + if (server.lastError.isNotBlank()) "\n⚠ ${server.lastError}" else ""
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                })
                listLayout.addView(row)
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                showMcpEditDialog(null) { render() }
            }
        }
        render()
        dialog.setOnDismissListener { refreshMcp() }
        dialog.show()
    }

    private fun showMcpEditDialog(existing: ZtMcpServerStore.McpServer?, onDone: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun field(hint: String, value: String, password: Boolean = false): EditText {
            val edit = EditText(this).apply {
                setHint(hint)
                setText(value)
                setSingleLine(true)
                textSize = 14f
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            layout.addView(edit)
            return edit
        }

        val nameEdit = field("名称（用于工具名前缀）", existing?.name.orEmpty())
        val urlEdit = field("URL（Streamable HTTP 端点）", existing?.url.orEmpty())
        val tokenEdit = field("Bearer Token（可空）", existing?.bearerToken.orEmpty(), password = true)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加 MCP 服务器" else "编辑 MCP 服务器")
            .setView(layout)
            .setPositiveButton("保存", null)
            .setNeutralButton(if (existing == null) "取消" else "删除", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val server = (existing ?: ZtMcpServerStore.McpServer()).apply {
                    name = nameEdit.text.toString().trim()
                    url = urlEdit.text.toString().trim()
                    bearerToken = tokenEdit.text.toString().trim()
                    authType = if (bearerToken.isBlank()) {
                        ZtMcpServerStore.AuthType.NONE
                    } else {
                        ZtMcpServerStore.AuthType.BEARER
                    }
                }
                if (server.url.isBlank()) {
                    UUtils.showMsg("URL 不能为空")
                    return@setOnClickListener
                }
                ZtMcpServerStore.upsert(server)
                ZtMcpToolBridge.invalidate()
                onDone()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (existing != null) {
                    ZtMcpServerStore.remove(existing.id)
                    ZtMcpToolBridge.invalidate()
                    onDone()
                }
                dialog.dismiss()
            }
            if (existing == null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = android.view.View.GONE
        }
        dialog.show()
    }

    // ─── 人格 ────────────────────────────────────────────────────────────────

    private fun showPersonaDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listLayout)

        val dialog = AlertDialog.Builder(this)
            .setTitle("人格")
            .setView(container)
            .setPositiveButton("新建", null)
            .setNeutralButton("关闭人格", null)
            .setNegativeButton("取消", null)
            .create()

        fun render() {
            listLayout.removeAllViews()
            val activeId = ZtPersonaStore.activeId()
            ZtPersonaStore.list().forEach { persona ->
                val row = TextView(this).apply {
                    text = buildString {
                        append(if (persona.id == activeId) "✓ " else "   ")
                        append(persona.avatar).append("  ").append(persona.name)
                        if (persona.builtIn) append("  ·内置")
                    }
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(12), 0, dp(12))
                    setOnClickListener {
                        ZtPersonaStore.saveActiveId(persona.id)
                        ZtPersonaStore.saveEnabled(true)
                        render()
                    }
                    setOnLongClickListener {
                        showPersonaEditDialog(persona) { render() }
                        true
                    }
                }
                listLayout.addView(row)
            }
            listLayout.addView(TextView(this).apply {
                text = "点击选择 · 长按编辑"
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, dp(8), 0, dp(8))
            })
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                showPersonaEditDialog(null) { render() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                ZtPersonaStore.saveEnabled(!ZtPersonaStore.isEnabled())
                render()
                dialog.dismiss()
            }
        }
        render()
        dialog.setOnDismissListener { refreshPersona() }
        dialog.show()
    }

    private fun showPersonaEditDialog(existing: ZtPersonaStore.Persona?, onDone: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun field(hint: String, value: String, singleLine: Boolean = true): EditText {
            val edit = EditText(this).apply {
                setHint(hint)
                setText(value)
                setSingleLine(singleLine)
                textSize = 14f
                if (!singleLine) {
                    minLines = 3
                    gravity = Gravity.TOP
                }
            }
            layout.addView(edit)
            return edit
        }

        val nameEdit = field("名称", existing?.name.orEmpty())
        val avatarEdit = field("头像 emoji", existing?.avatar.orEmpty().ifBlank { "🤖" })
        val personaEdit = field("Persona：性格与语气", existing?.persona.orEmpty(), singleLine = false)
        val boundaryEdit = field("Boundaries：不做什么 / 何时先问", existing?.boundary.orEmpty(), singleLine = false)
        val worldEdit = field("World：用户、环境、长期事实", existing?.world.orEmpty(), singleLine = false)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建人格" else "编辑人格")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("保存") { _, _ ->
                val persona = (existing?.copy() ?: ZtPersonaStore.Persona(
                    id = "p${System.currentTimeMillis()}"
                )).apply {
                    name = nameEdit.text.toString().trim().ifBlank { "未命名" }
                    avatar = avatarEdit.text.toString().trim().take(4).ifBlank { "🤖" }
                    persona = personaEdit.text.toString().trim()
                    boundary = boundaryEdit.text.toString().trim()
                    world = worldEdit.text.toString().trim()
                }
                ZtPersonaStore.upsert(persona)
                ZtPersonaStore.saveActiveId(persona.id)
                ZtPersonaStore.saveEnabled(true)
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─── 记忆 ────────────────────────────────────────────────────────────────

    private fun showMemoryDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val edit = EditText(this).apply {
            setText(ZtMemoryStore.readGlobal())
            textSize = 13f
            minLines = 10
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(ScrollView(this).apply {
            addView(edit)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
        })
        layout.addView(TextView(this).apply {
            text = "路径：filesDir/agent-memory/GLOBAL.md\nagent 会用 memory_append / memory_write 自行维护。"
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(8), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("长期记忆")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                ZtMemoryStore.writeGlobal(edit.text.toString())
                refreshMemory()
            }
            .setNeutralButton("清空") { _, _ ->
                ZtMemoryStore.writeGlobal("")
                refreshMemory()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
