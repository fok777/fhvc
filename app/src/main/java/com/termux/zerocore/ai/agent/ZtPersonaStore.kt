package com.termux.zerocore.ai.agent

import com.example.xh_lib.utils.SaveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 人格（Persona）存储：给 agent 一个稳定的性格与边界。
 *
 * 分三段单独编辑，拼装时合成：
 *   - persona   性格与语气
 *   - boundary  边界（不做什么 / 何时先问）
 *   - world     世界书：用户、环境、长期事实
 */
object ZtPersonaStore {

    private const val KEY_PERSONAS = "zt_personas_json"
    private const val KEY_ACTIVE = "zt_persona_active"
    private const val KEY_ENABLED = "zt_persona_enabled"

    private val gson = Gson()

    data class Persona(
        var id: String = "default",
        var name: String = "默认助手",
        /** 单字符 emoji 做头像，列表里好认。 */
        var avatar: String = "🤖",
        var persona: String = "",
        var boundary: String = "",
        var world: String = "",
        var builtIn: Boolean = false
    ) {
        /** 拼进系统提示词的正文；空段自动跳过。 */
        fun render(): String = buildString {
            if (persona.isNotBlank()) {
                appendLine("# Persona")
                appendLine(persona.trim())
                appendLine()
            }
            if (boundary.isNotBlank()) {
                appendLine("# Boundaries")
                appendLine(boundary.trim())
                appendLine()
            }
            if (world.isNotBlank()) {
                appendLine("# World")
                appendLine(world.trim())
            }
        }.trim()

        fun isEmpty(): Boolean =
            persona.isBlank() && boundary.isBlank() && world.isBlank()
    }

    // ─── 开关 ────────────────────────────────────────────────────────────────

    fun isEnabled(): Boolean = readString(KEY_ENABLED)?.let { it == "1" } ?: true

    fun saveEnabled(enabled: Boolean) {
        SaveData.saveStringOther(KEY_ENABLED, if (enabled) "1" else "0")
    }

    // ─── 列表 ────────────────────────────────────────────────────────────────

    fun list(): MutableList<Persona> {
        val json = readString(KEY_PERSONAS)
        val stored = if (json == null) mutableListOf() else try {
            val type = object : TypeToken<MutableList<Persona>>() {}.type
            gson.fromJson<MutableList<Persona>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
        // 内置预设始终在最前面，用户不能删但可以复制另存。
        val ids = stored.map { it.id }.toSet()
        val missing = presets().filter { it.id !in ids }
        return (missing + stored).toMutableList()
    }

    fun save(personas: List<Persona>) {
        // 内置预设不落盘，避免以后改预设时用户的旧副本盖掉新的。
        SaveData.saveStringOther(
            KEY_PERSONAS,
            gson.toJson(personas.filter { !it.builtIn })
        )
    }

    fun upsert(persona: Persona) {
        val all = list()
        val index = all.indexOfFirst { it.id == persona.id }
        if (index >= 0) all[index] = persona else all.add(persona)
        save(all)
    }

    fun remove(id: String) {
        if (presets().any { it.id == id }) return // 内置不可删
        save(list().filter { it.id != id })
        if (activeId() == id) saveActiveId("default")
    }

    fun find(id: String): Persona? = list().firstOrNull { it.id == id }

    fun activeId(): String = readString(KEY_ACTIVE) ?: "default"

    fun saveActiveId(id: String) {
        SaveData.saveStringOther(KEY_ACTIVE, id)
    }

    /** 当前生效的人格；未启用时返回 null。 */
    fun active(): Persona? {
        if (!isEnabled()) return null
        val persona = find(activeId()) ?: return null
        return if (persona.isEmpty()) null else persona
    }

    /** 拼进系统提示词的文本；无人格时返回空串。 */
    fun activePrompt(): String = active()?.render()?.takeIf { it.isNotBlank() }.orEmpty()

    // ─── 内置预设 ────────────────────────────────────────────────────────────

    /**
     * 四个预设覆盖手机端最常见的用法。文案刻意写得具体 —— 泛泛的
     * "你是一个有用的助手"对模型行为几乎没有影响。
     */
    fun presets(): List<Persona> = listOf(
        Persona(
            id = "default",
            name = "默认助手",
            avatar = "🤖",
            persona = "你是运行在手机 Termux 环境里的助手。回答直接、可操作，先给结论再给步骤。",
            boundary = "涉及删除、格式化、写入系统目录的操作，先说明风险并等用户确认。",
            world = "运行环境是 Android 上的 Termux，不是标准 Linux 服务器：没有 systemd，" +
                "路径前缀是 /data/data/com.termux/files/usr，很多 GNU 工具行为有差异。",
            builtIn = true
        ),
        Persona(
            id = "shell",
            name = "终端老手",
            avatar = "🐚",
            persona = "你是一个 Unix 终端老手。优先用一行命令解决问题，说明每个参数的作用，" +
                "并在命令有副作用时标注出来。",
            boundary = "不执行 rm -rf /、mkfs、dd 这类不可逆命令；不确定路径存在时先 ls 确认。",
            world = "",
            builtIn = true
        ),
        Persona(
            id = "teacher",
            name = "耐心的老师",
            avatar = "📚",
            persona = "你是一个耐心的老师。解释概念时先打比方再讲细节，" +
                "每讲完一段就确认对方是否跟得上。",
            boundary = "不直接给出完整答案就结束，要引导对方自己推一步。",
            world = "",
            builtIn = true
        ),
        Persona(
            id = "concise",
            name = "极简回复",
            avatar = "⚡",
            persona = "你极度简洁。能用一句话说完就不用两句，不写客套话，不复述用户的问题。",
            boundary = "需要确认的破坏性操作除外，该问还是要问。",
            world = "",
            builtIn = true
        )
    )

    private fun readString(key: String): String? {
        val raw = SaveData.getStringOther(key)
        if (raw.isNullOrBlank() || raw == "def") return null
        return raw
    }
}
