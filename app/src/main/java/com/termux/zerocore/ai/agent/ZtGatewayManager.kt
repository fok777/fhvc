package com.termux.zerocore.ai.agent

import com.termux.shared.termux.TermuxConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本地网关的通用安装 / 启停 / 探测。
 *
 * 与 [ZtOmniRouteManager] 的区别是不绑定具体项目：
 * 命令、端口、免密与否全部来自 [ZtLocalGateway] 描述符。
 */
object ZtGatewayManager {

    private const val TAG = "ZtGatewayManager"

    val homeDir = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val prefixDir = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val shellBin = "$prefixDir/bin/sh"
    private val nodeBin = "$prefixDir/bin/node"

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    data class Status(
        val running: Boolean,
        val modelCount: Int = 0,
        val detail: String = ""
    )

    fun isNodeInstalled(): Boolean = File(nodeBin).exists()

    fun nodeVersion(): String {
        if (!isNodeInstalled()) return ""
        val out = StringBuilder()
        runShell("$nodeBin --version 2>&1", wait = true) { out.append(it) }
        return out.toString().trim()
    }

    /**
     * Node 版本不足时服务能启动但每个请求都返回 500，且不报错，
     * 所以宁可提前拦下，不要等用户发现"能连上但全错"。
     */
    fun checkNode(gateway: ZtLocalGateway): String? {
        if (gateway.minNodeMajor <= 0) return null
        val raw = nodeVersion().trimStart('v')
        val major = raw.substringBefore('.').toIntOrNull()
        if (major == null) return "读不到 Node 版本，请先安装 nodejs"
        if (major < gateway.minNodeMajor) {
            return "Node 版本过低：$raw（需要 ${gateway.minNodeMajor}+）。" +
                "低于要求时服务能启动但请求全部返回 500，请先执行 pkg install nodejs"
        }
        return null
    }

    fun logFileFor(gateway: ZtLocalGateway): File =
        File(homeDir, "${gateway.id}.log")

    fun tailLog(gateway: ZtLocalGateway, lines: Int = 40): String {
        val file = logFileFor(gateway)
        if (!file.exists()) return ""
        return runCatching {
            file.readLines().takeLast(lines).joinToString("\n")
        }.getOrDefault("")
    }

    /** 探测网关是否活着。能列出模型才算真活着。 */
    fun probe(gateway: ZtLocalGateway, portOverride: Int = gateway.port): Status {
        if (portOverride <= 0) {
            return Status(false, detail = "未设置端口")
        }
        return try {
            val request = Request.Builder()
                .url(gateway.baseUrl(portOverride) + "/models")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Status(false, detail = "HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val count = runCatching {
                    JSONObject(body).optJSONArray("data")?.length() ?: 0
                }.getOrDefault(0)
                Status(running = true, modelCount = count, detail = "$count 个模型可用")
            }
        } catch (e: Exception) {
            Status(false, detail = e.message?.take(60) ?: "未运行")
        }
    }

    fun start(gateway: ZtLocalGateway, portOverride: Int = gateway.port): Boolean {
        val cmd = gateway.startCommand.replace("%PORT%", portOverride.toString())
        if (cmd.isBlank()) return false
        return runShell(
            cmd = "cd $homeDir; $cmd; exit 0",
            wait = false
        )
    }

    fun stop(gateway: ZtLocalGateway): Boolean {
        if (gateway.processPattern.isBlank()) return false
        // 用 pgrep 先确认，避免 pkill 匹配到自己所在 shell 导致误杀
        return runShell(
            cmd = "pkill -f \"${gateway.processPattern}\"; exit 0",
            wait = true
        )
    }

    /**
     * 安装。耗时很长，必须在后台线程调用，[onLine] 回传过程输出。
     * 逐步回传是为了让页面能显示进度，而不是干等几分钟没反馈。
     */
    fun install(gateway: ZtLocalGateway, onLine: (String) -> Unit): Boolean {
        if (gateway.installSteps.isEmpty()) {
            onLine("该网关无需安装")
            return true
        }
        for ((label, command) in gateway.installSteps) {
            onLine("▶ $label")
            val ok = runShell(cmd = "$command 2>&1", wait = true, onLine = onLine)
            if (!ok) {
                onLine("✗ $label 失败（见上方输出）")
                return false
            }
            onLine("✓ $label 完成")
        }
        onLine("安装完成")
        return true
    }

    private fun runShell(
        cmd: String,
        wait: Boolean,
        onLine: ((String) -> Unit)? = null
    ): Boolean {
        return try {
            val builder = ProcessBuilder(shellBin, "-c", cmd)
                .directory(File(homeDir))
            val env = builder.environment()
            env["PATH"] = "$prefixDir/bin:${env["PATH"] ?: "/system/bin"}"
            env["HOME"] = homeDir
            env["TMPDIR"] = TermuxConstants.TERMUX_FILES_DIR_PATH + "/tmp"
            builder.redirectErrorStream(true)

            val process = builder.start()
            if (!wait) {
                // 不等结束，但要把输出读掉，否则管道满了会卡住刚启动的进程
                Thread {
                    runCatching { process.inputStream.bufferedReader().readText() }
                    runCatching { process.waitFor() }
                }.apply { isDaemon = true }.start()
                return true
            }
            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { onLine?.invoke(it) }
            }
            process.waitFor() == 0
        } catch (e: Exception) {
            onLine?.invoke("✗ ${e.message}")
            android.util.Log.e(TAG, "shell failed", e)
            false
        }
    }
}
