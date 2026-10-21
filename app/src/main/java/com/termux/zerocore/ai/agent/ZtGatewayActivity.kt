package com.termux.zerocore.ai.agent

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.zerocore.settings.BaseTitleActivity
import kotlin.concurrent.thread

/**
 * 本地网关与免密直连端点的管理页。
 *
 * 用代码搭 UI 而不是写 layout，是因为这个页面会随上游项目增删频繁改动，
 * 改 Kotlin 比改 XML 少一步资源链接，也不容易因命名空间写错导致整包编译失败。
 */
class ZtGatewayActivity : BaseTitleActivity() {

    private val listView by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    }
    private val logView by lazy {
        TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
    }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "本机网关"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(listView)
        root.addView(TextView(this).apply {
            text = "免密直连（不用装任何东西）"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        })
        root.addView(buildDirectList())
        root.addView(TextView(this).apply {
            text = "输出"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        })
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        setBaseTitle("本地网关")
        rebuild()
    }

    private fun rebuild() {
        listView.removeAllViews()
        ZtLocalGateways.BUILTIN.forEach { gw ->
            val resolved = ZtLocalGateways.resolve(gw.id)
            listView.addView(card(resolved.name, resolved.summary) {
                showDetail(resolved)
            })
        }
    }

    private fun buildDirectList(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ZtLocalGateways.DIRECT_PRESETS.forEach { gw ->
            box.addView(card(gw.name, gw.summary) { showDirectDetail(gw) })
        }
        return box
    }

    private fun card(title: String, summary: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = "$title\n$summary"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#55000000"))
            setOnClickListener { onClick() }
        }.also {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            it.layoutParams = lp
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun log(line: String) {
        runOnUiThread { logView.append(line + "\n") }
    }

    // ─── 本地网关详情 ────────────────────────────────────────────────────────

    private fun showDetail(gw: ZtLocalGateway) {
        val portEdit = EditText(this).apply {
            setText(gw.port.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 13f
        }
        val cmdEdit = EditText(this).apply {
            setText(gw.startCommand)
            textSize = 11f
            setSingleLine(false)
            maxLines = 6
        }
        val modelEdit = EditText(this).apply {
            setText(gw.defaultModel)
            textSize = 13f
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(label("端口"))
            addView(portEdit)
            addView(label("默认模型"))
            addView(modelEdit)
            addView(label("启动命令（%PORT% 会被替换成上面端口）"))
            addView(cmdEdit)
            if (gw.notes.isNotBlank()) {
                addView(label("注意：" + gw.notes, grey = true))
            }
            if (gw.experimental) {
                addView(label("该网关上游尚不稳定，可能需要手动调整。", grey = true))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(gw.name)
            .setView(ScrollView(this).apply { addView(form) })
            .setNeutralButton("探测") { _, _ -> probeInto(gw, portEdit.text.toString().toIntOrNull() ?: gw.port) }
            .setNegativeButton("关闭", null)
            .setPositiveButton("详情操作") { _, _ ->
                val port = portEdit.text.toString().toIntOrNull() ?: gw.port
                saveAndOperate(gw, port, cmdEdit.text.toString(), modelEdit.text.toString())
            }
            .show()
    }

    private fun saveAndOperate(gw: ZtLocalGateway, port: Int, cmd: String, model: String) {
        val patch = org.json.JSONObject()
            .put("port", port)
            .put("startCommand", cmd)
            .put("defaultModel", model)
        ZtLocalGateways.saveOverride(gw.id, patch)
        val resolved = ZtLocalGateways.resolve(gw.id)
        showActions(resolved)
    }

    private fun showActions(gw: ZtLocalGateway) {
        val items = arrayOf(
            "启动", "停止", "安装 / 升级", "查看日志",
            "打开控制台", "接入当前 AI 配置"
        )
        AlertDialog.Builder(this)
            .setTitle("${gw.name} · ${gw.baseUrl()}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> doStart(gw)
                    1 -> doStop(gw)
                    2 -> doInstall(gw)
                    3 -> showLog(gw)
                    4 -> openDash(gw)
                    5 -> doApply(gw)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun doStart(gw: ZtLocalGateway) {
        if (busy) return
        if (!ZtGatewayManager.isNodeInstalled() && gw.runtime == ZtLocalGateway.Runtime.NODE) {
            log("尚未安装 Node，请先执行「安装 / 升级」")
            return
        }
        ZtGatewayManager.checkNode(gw)?.let {
            log(it)
            return
        }
        busy = true
        log("启动 ${gw.name}…")
        thread {
            val ok = ZtGatewayManager.start(gw)
            runOnUiThread {
                busy = false
                log(if (ok) "已发出启动命令，等几秒再探测" else "启动命令执行失败")
                logView.postDelayed({
                    val st = ZtGatewayManager.probe(gw)
                    log("探测：${if (st.running) "运行中 · ${st.detail}" else "未响应 · ${st.detail}"}")
                }, 5000)
            }
        }
    }

    private fun doStop(gw: ZtLocalGateway) {
        thread {
            ZtGatewayManager.stop(gw)
            runOnUiThread { log("已停止 ${gw.name}") }
        }
    }

    private fun doInstall(gw: ZtLocalGateway) {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle("安装 ${gw.name}")
            .setMessage("将依次执行：\n\n" +
                gw.installSteps.joinToString("\n") { "· ${it.first}" } +
                "\n\n耗时几分钟，需要联网。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                busy = true
                logView.text = ""
                thread {
                    ZtGatewayManager.install(gw) { log(it) }
                    runOnUiThread {
                        busy = false
                        val v = ZtGatewayManager.nodeVersion()
                        if (v.isNotBlank()) log("Node: $v")
                    }
                }
            }
            .show()
    }

    private fun showLog(gw: ZtLocalGateway) {
        val text = ZtGatewayManager.tailLog(gw, 60)
            .ifBlank { "（无日志）" }
        AlertDialog.Builder(this)
            .setTitle("${gw.name} 日志")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openDash(gw: ZtLocalGateway) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gw.dashUrl())))
        }.onFailure { log("无法打开浏览器：${gw.dashUrl()}") }
    }

    /** 把当前 AI 配置指向这个网关。免密的会把 key 清空并打上标记。 */
    private fun doApply(gw: ZtLocalGateway) {
        val current = ZtAgentAiConfigHelper.loadActiveConfig()
        val url = gw.baseUrl()
        val keyEdit = EditText(this).apply {
            setText(if (gw.keyless) "" else current.apiKey)
            textSize = 13f
            hint = if (gw.keyless) "免密，留空" else "API Key"
        }
        val modelEdit = EditText(this).apply {
            setText(gw.defaultModel.ifBlank { current.model })
            textSize = 13f
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(label(if (gw.keyless) "免密接入，请求不带 Authorization 头" else "API Key"))
            addView(keyEdit)
            addView(label("模型"))
            addView(modelEdit)
        }
        AlertDialog.Builder(this)
            .setTitle("接入 $url")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                val key = keyEdit.text.toString().trim()
                ZtAgentAiConfigHelper.saveKeyless(gw.keyless && key.isEmpty())
                ZtAgentAiConfigHelper.saveActiveFields(
                    url,
                    key,
                    modelEdit.text.toString().trim(),
                    current.systemPrompt
                )
                log("已接入 $url\n模型 ${modelEdit.text}${if (gw.keyless && key.isEmpty()) "（免密）" else ""}")
            }
            .show()
    }

    private fun probeInto(gw: ZtLocalGateway, port: Int) {
        thread {
            val st = ZtGatewayManager.probe(gw, port)
            runOnUiThread {
                log("探测 ${gw.baseUrl(port)}：${if (st.running) "运行中 · ${st.detail}" else "未响应 · ${st.detail}"}")
            }
        }
    }

    // ─── 免密直连 ────────────────────────────────────────────────────────────

    private val DIRECT_URLS = mapOf(
        "direct-kilo" to "https://api.kilo.ai/api/gateway/v1",
        "direct-vlmrun" to "https://gateway.vlm.run/v1/openai",
        "direct-ovh" to "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"
    )

    private fun showDirectDetail(gw: ZtLocalGateway) {
        val url = DIRECT_URLS[gw.id] ?: return
        val modelEdit = EditText(this).apply {
            setText(gw.defaultModel)
            textSize = 13f
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(label("端点：\n$url", grey = true))
            addView(label("模型"))
            addView(modelEdit)
            addView(label(gw.notes, grey = true))
        }
        AlertDialog.Builder(this)
            .setTitle(gw.name)
            .setView(form)
            .setNegativeButton("关闭", null)
            .setPositiveButton("接入") { _, _ ->
                val current = ZtAgentAiConfigHelper.loadActiveConfig()
                ZtAgentAiConfigHelper.saveKeyless(true)
                ZtAgentAiConfigHelper.saveActiveFields(
                    url,
                    "",
                    modelEdit.text.toString().trim(),
                    current.systemPrompt
                )
                log("已接入 $url（免密）")
            }
            .show()
    }

    private fun label(text: String, grey: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(if (grey) Color.LTGRAY else Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
            gravity = Gravity.START
        }
}
