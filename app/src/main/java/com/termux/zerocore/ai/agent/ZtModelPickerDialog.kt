package com.termux.zerocore.ai.agent

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 模型选择弹窗。
 *
 * 全部用代码搭 UI，**不需要改任何 layout XML** —— 这样接入只用在设置页加一行
 * `ZtModelPickerDialog.show(...)`,不必动 `activity_zt_agent_ai_settings.xml`。
 *
 * 搜索框 + 能力标签 + 上下文长度，顶部标注数据来自缓存还是本次联网，
 * 底部提供强制刷新与手动输入。
 */
object ZtModelPickerDialog {

    /** 文案集中在这里，正式发版可整体替换为 strings.xml + ZtLocaleStrings。 */
    object Text {
        const val TITLE = "选择模型"
        const val HINT_SEARCH = "搜索模型 ID…"
        const val LOADING = "正在从服务商拉取模型列表…"
        const val EMPTY = "没有匹配的模型。点「刷新」重新拉取，或选「手动输入」自己填 ID。"
        const val REFRESH = "刷新"
        const val MANUAL = "手动输入"
        const val CANCEL = "取消"
        const val FROM_CACHE = "来自缓存"
        const val FROM_NETWORK = "已更新"
        const val TOOLS = "工具"
        const val REASONING = "推理"
        const val VISION = "视觉"
    }

    interface OnModelPicked {
        /** 选中了列表里的模型。 */
        fun onPicked(model: ZtModelInfo)

        /** 用户选择自己填 ID；返回 true 表示调用方已处理。 */
        fun onManualInput(): Boolean = false
    }

    /**
     * 弹出选择器。
     * @param apiUrl 当前 API 地址，用于推导 /models 端点
     * @param apiKey 当前 API Key
     * @param currentId 当前已填的模型 ID，用于在列表里标记
     * @param forceRefresh 跳过缓存直接联网
     */
    fun show(
        context: Context,
        apiUrl: String,
        apiKey: String,
        currentId: String = "",
        forceRefresh: Boolean = false,
        onPicked: OnModelPicked
    ): AlertDialog {
        ZtModelCatalog.init(context)

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val searchEdit = EditText(context).apply {
            hint = Text.HINT_SEARCH
            setSingleLine(true)
            textSize = 14f
        }
        container.addView(
            searchEdit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val statusView = TextView(context).apply {
            text = Text.LOADING
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(8), 0, dp(4))
        }
        container.addView(statusView)

        val listView = ListView(context).apply {
            isTextFilterEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        container.addView(listView)

        val allModels = ArrayList<ZtModelInfo>()
        val adapter = ModelAdapter(context, allModels, currentId)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(context)
            .setTitle(Text.TITLE)
            .setView(container)
            .setPositiveButton(Text.REFRESH, null)
            .setNeutralButton(Text.MANUAL, null)
            .setNegativeButton(Text.CANCEL, null)
            .create()

        fun applyQuery(query: String) {
            val filtered = ZtModelCatalog.search(allModels, query)
            adapter.replace(filtered)
            if (allModels.isNotEmpty() && filtered.isEmpty()) {
                statusView.text = Text.EMPTY
            }
        }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyQuery(s?.toString().orEmpty())
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val model = adapter.getItem(position) ?: return@setOnItemClickListener
            onPicked.onPicked(model)
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            // 用 setOnShowListener 里取 button，避免点一次就自动 dismiss。
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                statusView.text = Text.LOADING
                adapter.clear()
                ZtModelCatalog.invalidate(apiUrl, apiKey)
                ZtModelCatalog.load(apiUrl, apiKey, forceRefresh = true, callback = makeCallback(
                    allModels, adapter, statusView, searchEdit, currentId
                ))
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                onPicked.onManualInput()
            }
        }

        ZtModelCatalog.load(
            apiUrl = apiUrl,
            apiKey = apiKey,
            forceRefresh = forceRefresh,
            callback = makeCallback(allModels, adapter, statusView, searchEdit, currentId)
        )

        dialog.show()
        return dialog
    }

    private fun makeCallback(
        allModels: ArrayList<ZtModelInfo>,
        adapter: ModelAdapter,
        statusView: TextView,
        searchEdit: EditText,
        currentId: String
    ): ZtModelCatalog.ModelsCallback {
        return object : ZtModelCatalog.ModelsCallback {
            override fun onModels(models: List<ZtModelInfo>, fromCache: Boolean) {
                allModels.clear()
                allModels.addAll(models)
                adapter.currentId = currentId
                adapter.replace(ZtModelCatalog.search(allModels, searchEdit.text?.toString().orEmpty()))
                val known = models.count { it.source != ZtModelSource.UNKNOWN }
                statusView.text = buildString {
                    append(if (fromCache) Text.FROM_CACHE else Text.FROM_NETWORK)
                    append(" · ${models.size} 个模型")
                    if (known < models.size) append(" · ${models.size - known} 个未识别")
                }
            }

            override fun onError(message: String) {
                statusView.text = message
                adapter.clear()
            }
        }
    }

    // ─── 列表适配器 ──────────────────────────────────────────────────────────

    private class ModelAdapter(
        context: Context,
        models: List<ZtModelInfo>,
        var currentId: String
    ) : ArrayAdapter<ZtModelInfo>(context, android.R.layout.simple_list_item_2, models.toMutableList()) {

        fun replace(models: List<ZtModelInfo>) {
            setNotifyOnChange(false)
            clear()
            addAll(models)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val view = super.getView(position, convertView, parent)
            val model = getItem(position) ?: return view

            val title = view.findViewById<TextView>(android.R.id.text1)
            val subtitle = view.findViewById<TextView>(android.R.id.text2)

            val active = model.id.equals(currentId, ignoreCase = true)
            val mark = if (active) "  ✓" else ""
            title.text = "${model.id}$mark"
            title.setSingleLine(true)
            title.textSize = 14f
            if (active) title.setTextColor(Color.parseColor("#48BAF3"))

            val badges = ArrayList<String>()
            if (model.supportsTools) badges.add(Text.TOOLS)
            if (model.supportsReasoning) badges.add(Text.REASONING)
            if (model.supportsVision) badges.add(Text.VISION)
            val ctx = if (model.contextLength >= 1_000_000) {
                "${model.contextLength / 1_000_000}M"
            } else {
                "${model.contextLength / 1_000}K"
            }
            subtitle.text = buildString {
                append(model.displayName)
                append(" · ${ctx}")
                if (badges.isNotEmpty()) append(" · ${badges.joinToString("/")}")
            }
            subtitle.setSingleLine(true)
            subtitle.textSize = 11f
            subtitle.gravity = Gravity.START
            return view
        }
    }
}
