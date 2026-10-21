package com.termux.zerocore.ai.agent

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.xh_lib.utils.SaveData
import com.termux.zerocore.settings.BaseTitleActivity
import kotlin.concurrent.thread

/**
 * 模型组管理。
 *
 * 组是「有序的一组模型 + 路由策略 + 共享限制」，对话时按组解析出实际模型 ID，
 * 失败则组内 fallback，所以成员顺序在这里是可编辑的载荷数据，不能自动重排。
 */
class ZtModelGroupActivity : BaseTitleActivity() {

    private val listBox by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    }
    private val logView by lazy {
        TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(actionRow("新建模型组") { showGroupEditor(null) })
        root.addView(actionRow("从服务商刷新模型目录") { refreshCatalog() })
        root.addView(listBox)
        root.addView(TextView(this).apply {
            text = "输出"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        setBaseTitle("模型组")
        rebuild()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun log(line: String) = runOnUiThread { logView.append(line + "\n") }

    private fun actionRow(title: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#55000000"))
            setOnClickListener { onClick() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun rebuild() {
        listBox.removeAllViews()
        val groups = ZtModelGroupStore.loadGroups()
        val activeId = ZtModelGroupStore.activeGroupId()
        if (groups.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "还没有模型组。点上方「新建模型组」，或先刷新目录。"
                textSize = 12f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }
        groups.forEach { group ->
            val members = ZtModelGroupStore.membersOf(group)
            val summary = buildString {
                append("成员 ${members.size}")
                if (group.isFallback) append(" · 失败自动切换")
                group.contextLimitTokens?.let { append(" · 上限 ${it / 1000}K") }
                if (group.id == activeId) append(" · 使用中")
            }
            listBox.addView(TextView(this).apply {
                text = "${group.name}\n$summary"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.parseColor("#55000000"))
                setOnClickListener { showGroupEditor(group) }
            }.also {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(8)
                it.layoutParams = lp
            })
        }
    }

    private fun refreshCatalog() {
        val config = ZtAgentAiConfigHelper.loadActiveConfig()
        if (config.apiUrl.isBlank()) {
            log("请先配置 API 地址")
            return
        }
        log("正在拉取模型目录…")
        thread {
            val result = ZtModelsApi.fetchModels(config.apiUrl, config.apiKey)
            runOnUiThread {
                val models = result.models
                if (models.isEmpty()) {
                    log("拉取失败：${result.error ?: "无模型返回"}")
                    return@runOnUiThread
                }
                ZtModelGroupStore.syncFromCatalog(models)
                log("已同步 ${models.size} 个模型到本地目录")
                rebuild()
            }
        }
    }

    // ─── 组编辑 ────────────────────────────────────────────────────────────

    private fun showGroupEditor(group: ZtModelGroup?) {
        val isNew = group == null
        val working = group ?: ZtModelGroup(
            id = "group_${System.currentTimeMillis()}",
            name = "新模型组"
        )

        val nameEdit = EditText(this).apply {
            setText(working.name); textSize = 13f
        }
        val contextEdit = EditText(this).apply {
            setText(working.contextLimitTokens?.toString() ?: "")
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "留空表示不限制"
            textSize = 13f
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(fieldLabel("组名"))
            addView(nameEdit)
            addView(fieldLabel("上下文上限（token，留空不限）"))
            addView(contextEdit)
        }

        AlertDialog.Builder(this)
            .setTitle(if (isNew) "新建模型组" else "编辑模型组")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setNeutralButton(if (isNew) "创建" else "管理成员") { _, _ ->
                val saved = working.copy(
                    name = nameEdit.text.toString().trim().ifBlank { working.name },
                    contextLimitTokens = contextEdit.text.toString().toIntOrNull()?.takeIf { it > 0 }
                )
                ZtModelGroupStore.upsertGroup(saved)
                if (ZtModelGroupStore.loadGroups().size == 1) {
                    ZtModelGroupStore.saveActiveGroup(saved.id)
                }
                log("已保存组：${saved.name}")
                rebuild()
                if (isNew) showMemberEditor(saved)
            }
            .setPositiveButton(if (isNew) "关闭" else "删除") { _, _ ->
                if (!isNew) {
                    ZtModelGroupStore.removeGroup(working.id)
                    log("已删除组：${working.name}")
                    rebuild()
                }
            }
            .show()
    }

    private fun showMemberEditor(group: ZtModelGroup) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val members = ZtModelGroupStore.membersOf(group).toMutableList()
        box.addView(fieldLabel("成员（按 fallback 顺序）"))
        members.forEachIndexed { index, entry ->
            box.addView(memberRow(group, members, index, entry))
        }
        box.addView(actionRow("添加模型") { pickModelToAdd(group) })

        val strategyLabel = TextView(this).apply {
            text = if (group.isFallback) "路由：失败自动切换下一个" else "路由：只用首选"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener {
                val next = if (group.isFallback) ZtModelGroup.ROUTE_SINGLE else ZtModelGroup.ROUTE_FALLBACK
                ZtModelGroupStore.upsertGroup(group.copy(strategy = next))
                log("路由策略：$next")
                rebuild()
            }
        }
        box.addView(strategyLabel)

        AlertDialog.Builder(this)
            .setTitle("${group.name} · 成员")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("关闭") { _, _ -> rebuild() }
            .setPositiveButton("设为当前") { _, _ ->
                ZtModelGroupStore.saveActiveGroup(group.id)
                log("已切换到组：${group.name}")
                rebuild()
            }
            .show()
    }

    private fun memberRow(
        group: ZtModelGroup,
        members: MutableList<ZtModelEntry>,
        index: Int,
        entry: ZtModelEntry
    ): TextView = TextView(this).apply {
        text = "${index + 1}. ${entry.displayName}\n${entry.modelId}"
        textSize = 12f
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(Color.parseColor("#33000000"))
        setOnClickListener {
            AlertDialog.Builder(this@ZtModelGroupActivity)
                .setTitle(entry.displayName)
                .setItems(arrayOf("上移", "下移", "编辑参数", "移出本组")) { _, which ->
                    val ids = group.memberIds.toMutableList()
                    when (which) {
                        0 -> if (index > 0) {
                            ids.removeAt(index); ids.add(index - 1, entry.modelId)
                        }
                        1 -> if (index < ids.lastIndex) {
                            ids.removeAt(index); ids.add(index + 1, entry.modelId)
                        }
                        2 -> { showEntryEditor(entry); return@setItems }
                        3 -> ids.remove(entry.modelId)
                    }
                    ZtModelGroupStore.upsertGroup(group.copy(memberIds = ids))
                    rebuild()
                    showMemberEditor(ZtModelGroupStore.findGroup(group.id) ?: group)
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun showEntryEditor(entry: ZtModelEntry) {
        val ctxEdit = EditText(this).apply {
            setText(entry.overrideContext?.toString() ?: ""); inputType = InputType.TYPE_CLASS_NUMBER
            hint = "目录值 ${entry.contextWindow ?: "未知"}"; textSize = 13f
        }
        val outEdit = EditText(this).apply {
            setText(entry.overrideMaxOutput?.toString() ?: ""); inputType = InputType.TYPE_CLASS_NUMBER
            hint = "目录值 ${entry.maxOutputTokens ?: "未知"}"; textSize = 13f
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(fieldLabel("上下文覆盖（留空用目录值）"))
            addView(ctxEdit)
            addView(fieldLabel("最大输出覆盖（留空用目录值）"))
            addView(outEdit)
            val levels = entry.availableThinkingLevels
            if (levels.isNotEmpty()) {
                addView(fieldLabel("思考档位：${levels.joinToString(" / ")}"))
                addView(TextView(this@ZtModelGroupActivity).apply {
                    text = "当前：${entry.thinkingLevel ?: "跟随组"}"
                    textSize = 12f
                    setTextColor(Color.LTGRAY)
                    setOnClickListener {
                        val options = (listOf("跟随组") + levels).toTypedArray()
                        AlertDialog.Builder(this@ZtModelGroupActivity)
                            .setItems(options) { _, i ->
                                val value = if (i == 0) null else options[i]
                                ZtModelGroupStore.upsertEntry(entry.copy(thinkingLevel = value))
                                log("${entry.modelId} 思考档位 → ${value ?: "跟随组"}")
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle(entry.modelId)
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                ZtModelGroupStore.upsertEntry(
                    entry.copy(
                        overrideContext = ctxEdit.text.toString().toIntOrNull()?.takeIf { it > 0 },
                        overrideMaxOutput = outEdit.text.toString().toIntOrNull()?.takeIf { it > 0 }
                    )
                )
                log("已保存 ${entry.modelId}")
            }
            .show()
    }

    /** 从目录选一个模型加进组；目录为空时允许手填 ID。 */
    private fun pickModelToAdd(group: ZtModelGroup) {
        val config = ZtAgentAiConfigHelper.loadActiveConfig()
        ZtModelPickerDialog.show(
            context = this,
            apiUrl = config.apiUrl,
            apiKey = config.apiKey,
            currentId = "",
            onPicked = object : ZtModelPickerDialog.OnModelPicked {
                override fun onPicked(model: ZtModelInfo) {
                    addModelToGroup(group, model)
                }

                override fun onManualInput(): Boolean {
                    val edit = EditText(this@ZtModelGroupActivity).apply {
                        hint = "模型 ID"; textSize = 13f
                    }
                    val wrap = LinearLayout(this@ZtModelGroupActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(8), dp(16), 0)
                        addView(edit)
                    }
                    AlertDialog.Builder(this@ZtModelGroupActivity)
                        .setTitle("手动添加模型")
                        .setView(wrap)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("添加") { _, _ ->
                            val id = edit.text.toString().trim()
                            if (id.isNotBlank()) {
                                addModelToGroup(group, ZtModelRegistry.resolve(id))
                            }
                        }
                        .show()
                    return true
                }
            }
        )
    }

    private fun addModelToGroup(group: ZtModelGroup, model: ZtModelInfo) {
        val existing = ZtModelGroupStore.loadEntries()[model.id]
        ZtModelGroupStore.upsertEntry(ZtModelEntry.fromModelInfo(model, existing))
        val ids = group.memberIds.toMutableList()
        if (!ids.contains(model.id)) ids.add(model.id)
        ZtModelGroupStore.upsertGroup(group.copy(memberIds = ids))
        log("已加入 ${model.id}")
        rebuild()
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.WHITE)
        setPadding(0, dp(8), 0, dp(2))
    }
}
