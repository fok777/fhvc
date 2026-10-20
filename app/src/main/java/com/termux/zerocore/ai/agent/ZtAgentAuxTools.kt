package com.termux.zerocore.ai.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆工具 + 技能按需加载。
 *
 * 技能按需加载：系统提示词里只放技能 ID 与一句话描述，模型判断需要用哪个了再调
 * read_agent_skill 取正文，避免把启用技能的正文全拼进提示词吃掉上下文。
 */
object ZtAgentAuxTools {

    private const val TAG = "ZtAgentAuxTools"

    const val MEMORY_SEARCH = "memory_search"
    const val MEMORY_APPEND = "memory_append"
    const val MEMORY_WRITE = "memory_write"
    const val SKILL_READ = "read_agent_skill"

    val memoryToolNames = setOf(MEMORY_SEARCH, MEMORY_APPEND, MEMORY_WRITE)
    val toolNames = memoryToolNames + SKILL_READ

    private const val MAX_SKILL_BODY_CHARS = 12_000

    fun addTools(tools: JSONArray) {
        if (ZtMemoryStore.isEnabled()) {
            tools.put(memorySearchTool())
            tools.put(memoryAppendTool())
            tools.put(memoryWriteTool())
        }
        tools.put(skillReadTool())
    }

    // ─── 定义 ────────────────────────────────────────────────────────────────

    private fun memorySearchTool(): JSONObject = tool(
        MEMORY_SEARCH,
        "Search long-term memory for notes relevant to a query. " +
            "Use before answering questions about user preferences, environment, or past work.",
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "query",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "What to look for, e.g. 'user editor preference'.")
                )
            )
            .put("required", JSONArray().put("query"))
    )

    private fun memoryAppendTool(): JSONObject = tool(
        MEMORY_APPEND,
        "Append one durable fact to long-term memory. Use for preferences, " +
            "environment quirks, and conventions — not for transient task state.",
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "fact",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "One self-contained sentence to remember.")
                )
            )
            .put("required", JSONArray().put("fact"))
    )

    private fun memoryWriteTool(): JSONObject = tool(
        MEMORY_WRITE,
        "Replace the entire long-term memory file. Only use when restructuring " +
            "or deleting existing notes — prefer memory_append for single facts.",
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "content",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Full new memory content in markdown.")
                )
            )
            .put("required", JSONArray().put("content"))
    )

    private fun skillReadTool(): JSONObject = tool(
        SKILL_READ,
        "Load the full body of a skill from ~/.skill by its id. " +
            "Call with no id to list available skill ids and descriptions.",
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "skill_id",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Skill id. Omit to list all.")
                )
            )
            .put("required", JSONArray())
    )

    // ─── 执行 ────────────────────────────────────────────────────────────────

    fun execute(toolCall: ZtAgentAiChatClient.ToolCall): String {
        val args = runCatching { JSONObject(toolCall.arguments.ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
        return when (toolCall.name) {
            MEMORY_SEARCH -> executeMemorySearch(args)
            MEMORY_APPEND -> executeMemoryAppend(args)
            MEMORY_WRITE -> executeMemoryWrite(args)
            SKILL_READ -> executeSkillRead(args)
            else -> "Error: unknown tool `${toolCall.name}`"
        }
    }

    private fun executeMemorySearch(args: JSONObject): String {
        if (!ZtMemoryStore.isEnabled()) return "Error: memory is disabled."
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return "Error: query is required."
        val hit = ZtMemoryStore.recall(query)
        return if (hit.isBlank()) {
            "No memory matched \"$query\". Long-term memory is empty or unrelated."
        } else {
            "Relevant memory:\n$hit"
        }
    }

    private fun executeMemoryAppend(args: JSONObject): String {
        if (!ZtMemoryStore.isEnabled()) return "Error: memory is disabled."
        val fact = args.optString("fact", "").trim()
        if (fact.isEmpty()) return "Error: fact is required."
        ZtMemoryStore.appendGlobal(fact)
        android.util.Log.i(TAG, "memory appended: ${fact.take(60)}")
        return "Saved to long-term memory: $fact"
    }

    private fun executeMemoryWrite(args: JSONObject): String {
        if (!ZtMemoryStore.isEnabled()) return "Error: memory is disabled."
        val content = args.optString("content", "").trim()
        if (content.isEmpty()) return "Error: content is required."
        ZtMemoryStore.writeGlobal(content)
        return "Long-term memory rewritten (${content.length} chars)."
    }

    private fun executeSkillRead(args: JSONObject): String {
        val id = args.optString("skill_id", "").trim()
        if (id.isEmpty()) {
            val all = ZtAgentAiSkillHelper.listAvailableSkills()
            if (all.isEmpty()) {
                return "No skills found in ${ZtAgentAiSkillHelper.skillsRootDisplayPath()}."
            }
            return buildString {
                appendLine("Available skills (call read_agent_skill with an id to load one):")
                all.forEach { s ->
                    append("- ${s.id}")
                    if (s.description.isNotBlank()) append(": ${s.description}")
                    appendLine()
                }
            }.trimEnd()
        }
        val file = ZtAgentAiSkillHelper.skillFile(id)
            ?: return "Error: skill `$id` not found in ${ZtAgentAiSkillHelper.skillsRootDisplayPath()}."
        val raw = runCatching { file.readText() }.getOrNull()
            ?: return "Error: cannot read skill file for `$id`."
        val body = stripFrontmatter(raw).trim()
        if (body.isEmpty()) return "Skill `$id` has an empty body."
        return "# Skill: $id\n\n${body.take(MAX_SKILL_BODY_CHARS)}"
    }

    /** 去掉 `---` 包裹的 frontmatter，只留正文。 */
    private fun stripFrontmatter(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("---")) return trimmed
        val end = trimmed.indexOf("\n---", 3)
        if (end < 0) return trimmed
        val after = trimmed.indexOf('\n', end + 1)
        return if (after < 0) "" else trimmed.substring(after + 1)
    }

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters)
            )
}

/**
 * 新增工具在 UI 上的状态文案。
 *
 * 刻意用常量而不是 string resource：这样接入本模块**不需要先补 strings.xml 就能编译**。
 * 要接多语言时，把这些常量替换成 `ZtLocaleStrings.getString(R.string.xxx)` 即可。
 */
object ZtNewToolLabels {
    const val MCP = "MCP 工具"
    const val MEMORY = "记忆"
    const val MEMORY_SEARCH = "记忆检索"
    const val MEMORY_APPEND = "记忆写入"
    const val READ_SKILL = "加载技能"
    const val SUBAGENT = "子智能体"
}
