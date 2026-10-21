package com.termux.zerocore.ai.agent

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.xh_lib.utils.UUtils
import com.termux.R
import com.termux.zerocore.settings.BaseTitleActivity
import kotlin.concurrent.thread

/**
 * OmniRoute 本地网关管理页。
 *
 * 把它做成页面而不是让用户在终端里手敲，是因为安装过程有三个坑会静默失败：
 * Node 版本差一个小版本就全站 500、~/.cache 缺失导致日志为空、后台进程被电池优化杀掉。
 * 这些在终端里都看不出来，页面可以做前置检查和明确提示。
 */
class ZtOmniRouteActivity : BaseTitleActivity() {

    private val statusDot by lazy { findViewById<View>(R.id.omni_status_dot) }
    private val statusText by lazy { findViewById<TextView>(R.id.omni_status_text) }
    private val endpointText by lazy { findViewById<TextView>(R.id.omni_endpoint_text) }
    private val btnStart by lazy { findViewById<TextView>(R.id.omni_btn_start) }
    private val btnStop by lazy { findViewById<TextView>(R.id.omni_btn_stop) }
    private val btnInstall by lazy { findViewById<TextView>(R.id.omni_btn_install) }
    private val btnDashboard by lazy { findViewById<TextView>(R.id.omni_btn_dashboard) }
    private val btnApply by lazy { findViewById<TextView>(R.id.omni_btn_apply) }
    private val btnPort by lazy { findViewById<TextView>(R.id.omni_btn_port) }
    private val logView by lazy { findViewById<TextView>(R.id.omni_log) }
    private val logScroll by lazy { findViewById<View>(R.id.omni_log_scroll) }

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zt_omniroute)
        setBaseTitle("OmniRoute 网关")

        btnStart.setOnClickListener { doStart() }
        btnStop.setOnClickListener { doStop() }
        btnInstall.setOnClickListener { doInstall() }
        btnDashboard.setOnClickListener { openDashboard() }
        btnApply.setOnClickListener { doApply() }
        btnPort.setOnClickListener { showPortDialog() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        btnPort.text = "端口：${ZtOmniRouteManager.port()}"
        endpointText.text = ZtOmniRouteManager.baseUrl()
        statusText.text = "检测中…"
        thread {
            val status = ZtOmniRouteManager.probe()
            runOnUiThread {
                statusText.text = if (status.running) {
                    "运行中 · ${status.detail}"
                } else {
                    "未运行 · ${status.detail}"
                }
                statusDot.setBackgroundColor(
                    if (status.running) Color.parseColor("#4CAF50") else Color.GRAY
                )
            }
        }
        val installed = ZtOmniRouteManager.isNodeInstalled()
        btnInstall.text = if (installed) "重新安装 / 升级 OmniRoute" else "安装 OmniRoute"
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logView.append(line + "\n")
            logScroll.post { (logScroll as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        runOnUiThread {
            btnStart.isEnabled = !value
            btnStop.isEnabled = !value
            btnInstall.isEnabled = !value
            btnApply.isEnabled = !value
        }
    }

    private fun doStart() {
        if (busy) return
        if (!ZtOmniRouteManager.isNodeInstalled()) {
            appendLog("尚未安装 Node，请先点「安装 OmniRoute」")
            return
        }
        if (!ZtOmniRouteManager.isNodeVersionOk()) {
            val version = ZtOmniRouteManager.nodeVersion()
            appendLog("Node 版本过低：$version（需要 22.22.2+ 或 24+）。\n" +
                "低于要求时服务能启动但每个请求都返回 500，请先升级：pkg install nodejs")
            return
        }
        setBusy(true)
        appendLog("启动中…")
        thread {
            val ok = ZtOmniRouteManager.start()
            runOnUiThread {
                setBusy(false)
                appendLog(if (ok) "已发出启动命令，等待就绪…" else "启动失败")
                // 刚 fork 出去还没监听端口，等一下再探
                logScroll.postDelayed({ refresh() }, 4000)
            }
        }
    }

    private fun doStop() {
        if (busy) return
        setBusy(true)
        thread {
            ZtOmniRouteManager.stop()
            runOnUiThread {
                setBusy(false)
                appendLog("已停止")
                refresh()
            }
        }
    }

    private fun doInstall() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle("安装 OmniRoute")
            .setMessage(
                "会执行 pkg install nodejs python build-essential git 与 npm install -g omniroute。\n\n" +
                    "耗时几分钟，需要联网。\n\n" +
                    "注意：不要从 Play 商店安装 Termux，那个版本已停止更新。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ -> runInstall() }
            .show()
    }

    private fun runInstall() {
        setBusy(true)
        logView.text = ""
        thread {
            ZtOmniRouteManager.install { appendLog(it) }
            runOnUiThread {
                setBusy(false)
                val version = ZtOmniRouteManager.nodeVersion()
                if (version.isNotBlank()) appendLog("Node: $version")
                refresh()
            }
        }
    }

    private fun openDashboard() {
        val url = ZtOmniRouteManager.dashboardUrl()
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            appendLog("无法打开浏览器：$url")
        }
    }

    /** 把当前 AI 配置指向本机网关，模型设为 auto 让它自己挑。 */
    private fun doApply() {
        if (busy) return
        val current = ZtAgentAiConfigHelper.loadActiveConfig()
        val target = ZtOmniRouteManager.baseUrl()
        val keyEdit = EditText(this).apply {
            setText(if (current.apiKey.isBlank()) "omniroute" else current.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply {
                text = "API Key 从控制台 Endpoints 页拷贝；本机自用填任意值也可以。"
                setTextColor(resources.getColor(R.color.md_grey_500))
                textSize = 12f
            })
            addView(keyEdit)
        }
        AlertDialog.Builder(this)
            .setTitle("接入 $target")
            .setView(wrapper)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                ZtAgentAiConfigHelper.saveActiveFields(
                    target,
                    keyEdit.text.toString().trim(),
                    "auto",
                    current.systemPrompt
                )
                appendLog("已接入：\n地址 $target\n模型 auto")
                refresh()
            }
            .show()
    }

    private fun showPortDialog() {
        val edit = EditText(this).apply {
            setText(ZtOmniRouteManager.port().toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle("网关端口")
            .setView(wrapper)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val port = edit.text.toString().toIntOrNull()
                if (port != null) {
                    ZtAgentAiConfigHelper.saveOmniRoutePort(port)
                    appendLog("端口改为 $port，需要重启网关")
                    refresh()
                }
            }
            .show()
    }
}
