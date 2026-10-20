package com.termux.zerocore.ai.agent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆：全局长期记忆 + 按会话的日记。
 *
 * 用 markdown 文件而不是数据库，这样 agent 能用文件工具读写、用户也能在终端里直接改。
 *
 * 目录：filesDir/agent-memory/
 *   GLOBAL.md          全局记忆
 *   sessions/<id>.md   会话日记
 */
object ZtMemoryStore {

    private const val TAG = "ZtMemoryStore"
    private const val DIR = "agent-memory"
    const val GLOBAL_FILE = "GLOBAL.md"

    /** 全局记忆注入提示词的字符上限，避免用户写太长把上下文撑爆。 */
    private const val MAX_GLOBAL_CHARS = 4_000
    private const val MAX_RECALL_CHARS = 2_000

    @Volatile
    private var appContext: Context? = null

    /** 当前会话 ID，由 agent 面板在进入会话时设置；为空则用 "default"。 */
    @Volatile
    var sessionId: String = "default"

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun root(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, DIR).apply { mkdirs() }
    }

    fun globalFile(): File? = root()?.let { File(it, GLOBAL_FILE) }

    fun sessionFile(id: String = sessionId): File? {
        val root = root() ?: return null
        val dir = File(root, "sessions").apply { mkdirs() }
        val safe = id.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "default" }
        return File(dir, "$safe.md")
    }

    // ─── 全局开关 ────────────────────────────────────────────────────────────

    fun isEnabled(): Boolean = ZtAgentMemoryPrefs.isEnabled()

    fun saveEnabled(enabled: Boolean) = ZtAgentMemoryPrefs.saveEnabled(enabled)

    // ─── 读写 ────────────────────────────────────────────────────────────────

    fun readGlobal(): String = runCatching { globalFile()?.readText().orEmpty() }.getOrDefault("")

    fun writeGlobal(text: String) {
        runCatching { globalFile()?.writeText(text) }
            .onFailure { android.util.Log.w(TAG, "write global failed: ${it.message}") }
    }

    fun appendGlobal(entry: String) {
        val body = entry.trim()
        if (body.isEmpty()) return
        val current = readGlobal()
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val next = if (current.isBlank()) {
            "- [$stamp] $body\n"
        } else {
            current.trimEnd() + "\n- [$stamp] $body\n"
        }
        writeGlobal(next)
    }

    fun readSession(id: String = sessionId): String =
        runCatching { sessionFile(id)?.readText().orEmpty() }.getOrDefault("")

    fun appendSession(entry: String, id: String = sessionId) {
        val body = entry.trim()
        if (body.isEmpty()) return
        val f = sessionFile(id) ?: return
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        runCatching { f.appendText("- [$stamp] $body\n") }
            .onFailure { android.util.Log.w(TAG, "append session failed: ${it.message}") }
    }

    fun listSessions(): List<String> {
        val dir = root()?.let { File(it, "sessions") } ?: return emptyList()
        return dir.listFiles()?.orEmpty()
            ?.filter { it.isFile && it.extension == "md" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            .orEmpty()
    }

    fun deleteSession(id: String) {
        runCatching { sessionFile(id)?.delete() }
    }

    fun clearAll() {
        runCatching { root()?.deleteRecursively() }
    }

    // ─── 召回 ────────────────────────────────────────────────────────────────

    /**
     * 关键词召回：把记忆按行（或空行分段）切开，对每段与查询做词重叠打分，
     * 取分最高的若干段拼回去。
     *
     * 中文没有空格，所以用「字符 bigram」做切分 —— 这是不引入分词库时
     * 效果尚可的低成本做法。
     */
    fun recall(query: String, limit: Int = 6): String {
        val global = readGlobal()
        if (global.isBlank()) return ""
        val q = query.trim().lowercase()
        if (q.isEmpty()) return global.take(MAX_RECALL_CHARS)

        val chunks = global.split(Regex("\n(?=[-\\d*#])"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (chunks.isEmpty()) return global.take(MAX_RECALL_CHARS)

        val qGrams = grams(q)
        val scored = chunks.map { chunk ->
            val cGrams = grams(chunk.lowercase())
            var score = 0
            for (g in qGrams) if (g in cGrams) score++
            // 归一化，避免长段落因为词多而永远排第一。
            val norm = if (cGrams.isEmpty()) 0.0 else score.toDouble() / cGrams.size
            chunk to (score * 10 + norm)
        }.sortedByDescending { it.second }

        val picked = scored.take(limit).filter { it.second > 0 }
        if (picked.isEmpty()) return ""
        return picked.joinToString("\n") { it.first }.take(MAX_RECALL_CHARS)
    }

    private fun grams(text: String): Set<String> {
        val cleaned = text.replace(Regex("[\\s\\p{Punct}]+"), "")
        if (cleaned.length < 2) return setOf(cleaned)
        val out = HashSet<String>()
        for (i in 0 until cleaned.length - 1) out.add(cleaned.substring(i, i + 2))
        return out
    }

    // ─── 注入系统提示词 ──────────────────────────────────────────────────────

    /** 全局记忆全文（截断后），注入系统提示词用。 */
    fun globalPrompt(): String {
        if (!isEnabled()) return ""
        val text = readGlobal().trim()
        if (text.isEmpty()) return ""
        return "# Long-term memory (GLOBAL.md)\n${text.take(MAX_GLOBAL_CHARS)}"
    }

    /**
     * 记忆工具的使用说明，只在启用时注入。
     * 明确写了"什么时候该记"，否则模型要么什么都不记、要么什么都记。
     */
    fun toolsPrompt(): String {
        if (!isEnabled()) return ""
        return "# Memory\n" +
            "You have persistent memory via these tools:\n" +
            "- `memory_search`: recall relevant notes before answering questions about " +
            "the user's preferences, environment, or past work.\n" +
            "- `memory_append`: save durable facts — preferences, environment quirks, " +
            "project conventions. Do NOT save transient task state.\n" +
            "- `memory_write`: replace the whole long-term memory file. Use sparingly.\n" +
            "Save when the user states a preference or corrects you. Don't save what " +
            "one tool call can re-derive."
    }
}

/** 记忆开关放在独立对象里，避免与 ZtMemoryStore 的文件逻辑混在一起。 */
internal object ZtAgentMemoryPrefs {
    private const val KEY = "zt_memory_enabled"
    fun isEnabled(): Boolean =
        com.example.xh_lib.utils.SaveData.getStringOther(KEY)?.let { it == "1" } ?: true
    fun saveEnabled(enabled: Boolean) {
        com.example.xh_lib.utils.SaveData.saveStringOther(KEY, if (enabled) "1" else "0")
    }
}
