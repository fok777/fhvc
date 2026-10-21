package com.termux.zerocore.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地网关描述符。
 *
 * 这些项目形态各异（npm 全局包、git clone 单文件、pip 包），但对外都是
 * 「监听一个端口 + OpenAI 兼容」，所以统一抽象成安装/启动/探测三件事。
 *
 * 端口与启动命令可能因上游改动而失效，因此每一项都允许用户在页面里改，
 * 改完存本地覆盖预置值，而不是硬编码。
 */
data class ZtLocalGateway(
    val id: String,
    val name: String,
    val summary: String,
    /** 安装步骤，按顺序执行，任一步失败即中止。 */
    val installSteps: List<Pair<String, String>>,
    /** 启动命令，会在 $HOME 下以 sh -c 执行，内部自行 nohup。 */
    val startCommand: String,
    /** 用于 pkill 匹配。 */
    val processPattern: String,
    val port: Int,
    val basePath: String = "/v1",
    val defaultModel: String = "",
    /**
     * 免密：请求不带 Authorization 头。
     * 有些网关带任何 bearer 都会返回 401，必须能不发。
     */
    val keyless: Boolean = false,
    /** 需要的最低 Node 主版本号，0 表示不检查。 */
    val minNodeMajor: Int = 0,
    /** 运行期：决定用哪套 PATH 与包管理器。 */
    val runtime: Runtime = Runtime.NODE,
    /** 页面展示的注意事项。 */
    val notes: String = "",
    /** 是否还处于早期/未发布状态，页面会标注。 */
    val experimental: Boolean = false
) {
    enum class Runtime { NODE, PYTHON }

    fun baseUrl(portOverride: Int = port): String = "http://127.0.0.1:$portOverride$basePath"
    fun dashUrl(portOverride: Int = port): String = "http://127.0.0.1:$portOverride"
}

/**
 * GitHub 直连不通时的镜像前缀。
 *
 * 国内网络下 github.com:443 经常超时，git clone / npm 都会失败，
 * 所以克隆地址不能写死，得能换前缀重试。
 */
object ZtGitMirror {

    /** 每项为「显示名 → 前缀」，空串表示直连。拼法是 前缀 + 原始 url。 */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "直连 github.com" to "",
        "ghfast" to "https://ghfast.top/",
        "gh-proxy" to "https://gh-proxy.com/",
        "gitmirror" to "https://hub.gitmirror.com/",
        "gh-proxy.net" to "https://gh-proxy.net/"
    )

    private const val KEY = "zt_git_mirror"

    fun current(): String = OPTIONS.getOrNull(readIndex())?.second ?: ""

    fun currentLabel(): String = OPTIONS.getOrNull(readIndex())?.first ?: OPTIONS[0].first

    fun saveIndex(index: Int) {
        runCatching {
            com.example.xh_lib.utils.SaveData.saveStringOther(
                KEY, index.coerceIn(0, OPTIONS.lastIndex).toString()
            )
        }
    }

    private fun readIndex(): Int = runCatching {
        com.example.xh_lib.utils.SaveData.getStringOther(KEY)?.toIntOrNull()
    }.getOrNull() ?: 0

    /** 把原始 GitHub 地址套上当前镜像前缀。 */
    fun apply(rawUrl: String): String {
        val prefix = current()
        return if (prefix.isEmpty()) rawUrl else prefix + rawUrl
    }
}

object ZtLocalGateways {

    private const val PKG = "pkg install -y"
    private const val PRE = "\$PREFIX/bin"

    val BUILTIN: List<ZtLocalGateway> = listOf(

        ZtLocalGateway(
            id = "omniroute",
            name = "OmniRoute",
            summary = "聚合 290+ 提供商，自动降级，内置压缩与 MCP",
            installSteps = listOf(
                "安装 Node 与编译工具" to "$PKG nodejs python build-essential git",
                "安装 OmniRoute" to "$PRE/npm install -g omniroute"
            ),
            startCommand = "export PATH=$PRE:\$PATH; " +
                "export DATA_DIR=\$HOME/.omniroute; " +
                "export PORT=%PORT%; " +
                "mkdir -p \$HOME/.cache; " +
                "nohup omniroute > \$HOME/omniroute.log 2>&1 & disown",
            processPattern = "omniroute",
            port = 20128,
            defaultModel = "auto",
            minNodeMajor = 22,
            notes = "Node 必须 22.22.2+ 或 24+；低于要求时服务能起但每个请求都返回 500。" +
                "首次启动后请到控制台改掉默认密码 CHANGEME。"
        ),

        ZtLocalGateway(
            id = "zen-proxy",
            name = "Zen Proxy",
            summary = "把 OpenCode Zen 免费层暴露成 OpenAI 接口，零依赖单文件",
            installSteps = listOf(
                "安装 Node 与 git" to "$PKG nodejs git",
                "拉取源码" to "rm -rf \$HOME/zen-proxy && " +
                    "git clone --depth 1 %MIRROR%https://github.com/12errh/zen-proxy.git \$HOME/zen-proxy"
            ),
            startCommand = "cd \$HOME/zen-proxy && " +
                "export PATH=$PRE:\$PATH; " +
                "nohup node zen-proxy.mjs --port %PORT% > \$HOME/zen-proxy.log 2>&1 & disown",
            processPattern = "zen-proxy",
            port = 8787,
            defaultModel = "",
            minNodeMajor = 18,
            notes = "它负责注入 x-opencode-session 等头，所以自己别去改请求头。" +
                "免费层按 IP 计配额，打满会 429。"
        ),

        ZtLocalGateway(
            id = "freellmpool",
            name = "freellmpool",
            summary = "Python 实现，聚合 22 家免费层，支持代理与 MCP",
            installSteps = listOf(
                "安装 Python" to "$PKG python",
                "安装 freellmpool" to "$PRE/pip install freellmpool",
                "升级 pip" to "$PRE/pip install --upgrade pip"
            ),
            startCommand = "export PATH=$PRE:\$PATH; " +
                "nohup freellmpool proxy --port %PORT% > \$HOME/freellmpool.log 2>&1 & disown",
            processPattern = "freellmpool",
            port = 8788,
            runtime = ZtLocalGateway.Runtime.PYTHON,
            notes = "启动参数随版本变化，若起不来请按上游 README 调整启动命令。"
        ),

        ZtLocalGateway(
            id = "our-free-model",
            name = "Our Free Model",
            summary = "dsh 插件的独立服务版，免登录直连免费车道",
            installSteps = listOf(
                "安装 Node 与 git" to "$PKG nodejs git",
                "拉取仓库" to "rm -rf \$HOME/our-free-model && " +
                    "git clone --depth 1 %MIRROR%https://github.com/Ebony-Vinyl/dsh-our-free-model.git " +
                    "\$HOME/our-free-model",
                "安装依赖" to "cd \$HOME/our-free-model/packages/standalone && " +
                    "export PATH=$PRE:\$PATH && npm install"
            ),
            startCommand = "cd \$HOME/our-free-model/packages/standalone && " +
                "export PATH=$PRE:\$PATH; " +
                "nohup npm start > \$HOME/our-free-model.log 2>&1 & disown",
            processPattern = "our-free-model",
            port = 18900,
            minNodeMajor = 22,
            experimental = true,
            notes = "上游 README 标注独立服务的安装制品尚未发布，可能需要从源码运行。" +
                "免登录但按 session 计速率，打满会 429。"
        )
    )

    private fun storeFile(): File =
        File(ZtGatewayManager.homeDir, ".zt_gateways.json")

    /** 读用户改过的端口与启动命令，按 id 覆盖预置值。 */
    fun loadOverrides(): Map<String, JSONObject> {
        val file = storeFile()
        if (!file.exists()) return emptyMap()
        return runCatching {
            val obj = JSONObject(file.readText())
            val out = linkedMapOf<String, JSONObject>()
            obj.keys().forEach { key ->
                out[key] = obj.getJSONObject(key)
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun resolve(id: String): ZtLocalGateway {
        val base = BUILTIN.firstOrNull { it.id == id } ?: BUILTIN.first()
        val ov = loadOverrides()[id] ?: return base
        return base.copy(
            port = ov.optInt("port", base.port),
            startCommand = ov.optString("startCommand", base.startCommand),
            keyless = if (ov.has("keyless")) ov.optBoolean("keyless") else base.keyless,
            defaultModel = ov.optString("defaultModel", base.defaultModel)
        )
    }

    fun saveOverride(id: String, patch: JSONObject) {
        val file = storeFile()
        val all = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val existing = if (all.has(id)) all.getJSONObject(id) else JSONObject()
        patch.keys().forEach { existing.put(it, patch.get(it)) }
        all.put(id, existing)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(all.toString(2))
        }
    }

    fun resetOverride(id: String) {
        val file = storeFile()
        if (!file.exists()) return
        runCatching {
            val all = JSONObject(file.readText())
            all.remove(id)
            file.writeText(all.toString(2))
        }
    }

    /** 免密的直连端点，不需要装任何东西。 */
    val DIRECT_PRESETS: List<ZtLocalGateway> = listOf(
        ZtLocalGateway(
            id = "direct-zen",
            name = "OpenCode Zen 免费层",
            summary = "免 key 匿名直连，多个 -free 模型，工具调用与推理都支持",
            installSteps = emptyList(),
            startCommand = "",
            processPattern = "",
            port = 0,
            basePath = "",
            defaultModel = "deepseek-v4-flash-free",
            keyless = true,
            notes = "凭证是字面量 public，无需注册。" +
                "上游会校验客户端指纹，偶发 FreeTierError 属正常；免费额度按出口 IP 共享。"
        ),
        ZtLocalGateway(
            id = "direct-kilo",
            name = "Kilo 免费池",
            summary = "直连公共网关，200 次/小时，模型 kilo-auto/free 自动选",
            installSteps = emptyList(),
            startCommand = "",
            processPattern = "",
            port = 0,
            basePath = "",
            defaultModel = "kilo-auto/free",
            keyless = true,
            notes = "上游声明 prompt 可能被记录用于改进服务，敏感内容不要用。"
        ),
        ZtLocalGateway(
            id = "direct-vlmrun",
            name = "VLM Run 匿名层",
            summary = "100 次/天，完全匿名（国内常连不上）",
            installSteps = emptyList(),
            startCommand = "",
            processPattern = "",
            port = 0,
            basePath = "",
            defaultModel = "qwen/qwen3.8-27b",
            keyless = true,
            notes = "带任何 Authorization 头都会 401，必须免密。国内网络常超时，先测连通再用。"
        ),
        ZtLocalGateway(
            id = "direct-ovh",
            name = "OVHcloud 匿名层",
            summary = "欧洲机房，2 RPM，适合 GDPR 场景（国内常连不上）",
            installSteps = emptyList(),
            startCommand = "",
            processPattern = "",
            port = 0,
            basePath = "",
            defaultModel = "Meta-Llama-3_3-70B-Instruct",
            keyless = true,
            notes = "只有 2 次/分钟，仅适合低频与合规场景。国内网络常超时，先测连通再用。"
        )
    )
}
