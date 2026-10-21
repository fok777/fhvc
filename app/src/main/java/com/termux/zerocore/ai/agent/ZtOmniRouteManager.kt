package com.termux.zerocore.ai.agent

import com.termux.shared.termux.TermuxConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OmniRoute 网关的生命周期管理。
 *
 * OmniRoute 是 Node 应用，Docker 镜像在手机上起不来（内核没有容器运行时），
 * 官方本来就有 Termux 的 headless 方案，所以这里直接管进程。
 */
object ZtOmniRouteManager {

    private const val TAG = "ZtOmniRouteManager"

    const val DEFAULT_PORT = 20128
    const val BASE_PATH = "/v1"

    private val prefixDir = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val homeDir = TermuxConstants.TERMUX_HOME_DIR_PATH

    private val shellBin = "$prefixDir/bin/sh"
    private val nodeBin = "$prefixDir/bin/node"
    private val npmBin = "$prefixDir/bin/npm"
    private val pkgBin = "$prefixDir/bin/pkg"

    private val logFile = File(homeDir, "omniroute.log")
    private val dataDir = File(homeDir, ".omniroute")
    private val cacheDir = File(homeDir, ".cache")

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    data class Status(
        val running: Boolean,
        val modelCount: Int = 0,
        val detail: String = ""
    )

    /** Node 是否可用；不可用说明没装过或环境坏了。 */
    fun isNodeInstalled(): Boolean = File(nodeBin).exists()

    fun port(): Int = ZtAgentAiConfigHelper.omniRoutePort()

    fun baseUrl(): String = "http://127.0.0.1:${port()}$BASE_PATH"

    fun dashboardUrl(): String = "http://127.0.0.1:${port()}"

    /** 探测网关是否在跑。能列出模型才算真活着。 */
    fun probe(): Status {
        return try {
            val request = Request.Builder()
                .url("http://127.0.0.1:${port()}/v1/models")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Status(false, detail = "HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val count = runCatching {
                    val arr = org.json.JSONObject(body).optJSONArray("data")
                    arr?.length() ?: 0
                }.getOrDefault(0)
                Status(running = true, modelCount = count, detail = "$count 个模型可用")
            }
        } catch (e: Exception) {
            Status(false, detail = e.message?.take(60) ?: "未运行")
        }
    }

    /** 后台启动。nohup + disown，避免父进程退出时被拖走。 */
    fun start(): Boolean {
        if (!isNodeInstalled()) return false
        // Next.js 在 platform == android 上需要 ~/.cache 已存在，否则 instrument hook 加载失败，
        // 表现为所有请求返回裸 500 且日志为空。
        cacheDir.mkdirs()
        dataDir.mkdirs()
        val cmd = "cd $homeDir && " +
            "export PATH=$prefixDir/bin:\$PATH && " +
            "export HOME=$homeDir && " +
            "export DATA_DIR=$dataDir && " +
            "export PORT=${port()} && " +
            "nohup omniroute > ${logFile.absolutePath} 2>&1 & " +
            "disown; exit 0"
        return runShell(cmd, wait = false)
    }

    fun stop(): Boolean {
        val cmd = "export PATH=$prefixDir/bin:\$PATH; " +
            "pkill -f omniroute; " +
            "pkill -f 'node.*omniroute'; " +
            "exit 0"
        return runShell(cmd, wait = true)
    }

    /** 日志尾部，用于页面展示启动失败原因。 */
    fun tailLog(lines: Int = 40): String {
        if (!logFile.exists()) return ""
        return runCatching {
            logFile.readLines().takeLast(lines).joinToString("\n")
        }.getOrDefault("")
    }

    /**
     * 安装或升级。耗时很长，必须在后台线程调用，通过 [onLine] 回传输出。
     * 分成两步是为了让页面能分阶段提示，而不是干等几分钟没有任何反馈。
     */
    fun install(onLine: (String) -> Unit): Boolean {
        cacheDir.mkdirs()
        val steps = listOf(
            "安装 Node 与编译工具" to
                "$pkgBin install -y nodejs python build-essential git",
            "安装 OmniRoute" to
                "$npmBin install -g omniroute"
        )
        for ((label, command) in steps) {
            onLine("▶ $label")
            val ok = runShell(
                cmd = "$command 2>&1",
                wait = true,
                onLine = onLine
            )
            if (!ok) {
                onLine("✗ $label 失败")
                return false
            }
            onLine("✓ $label 完成")
        }
        onLine("全部完成，可以启动了")
        return true
    }

    /** 校验 Node 版本是否满足 OmniRoute 的要求。 */
    fun nodeVersion(): String {
        if (!isNodeInstalled()) return ""
        val out = StringBuilder()
        runShell("$nodeBin --version 2>&1", wait = true) { out.append(it) }
        return out.toString().trim()
    }

    /**
     * Node 版本低于 22.22.2 时，服务能起来但每个真实请求都返回 500，
     * 且不报错——所以宁可提前拦下。
     */
    fun isNodeVersionOk(): Boolean {
        val raw = nodeVersion().trimStart('v')
        val major = raw.substringBefore('.').toIntOrNull() ?: return false
        return major >= 22
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
            env["DATA_DIR"] = dataDir.absolutePath
            builder.redirectErrorStream(true)

            val process = builder.start()
            if (!wait) {
                // 不等结束，但要读掉一点输出防止管道堵塞，否则启动的进程会卡住
                Thread {
                    runCatching { process.inputStream.bufferedReader().readText() }
                    runCatching { process.waitFor() }
                }.apply { isDaemon = true }.start()
                return true
            }
            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { onLine?.invoke(it) }
            }
            val code = process.waitFor()
            code == 0
        } catch (e: Exception) {
            onLine?.invoke("✗ ${e.message}")
            android.util.Log.e(TAG, "shell failed", e)
            false
        }
    }
}
