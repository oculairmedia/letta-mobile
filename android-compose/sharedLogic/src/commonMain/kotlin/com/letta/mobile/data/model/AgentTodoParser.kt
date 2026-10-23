package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parse an agent-authored replacement plan. TodoWrite uses `todos/content/activeForm`;
 * Letta Code's Codex-compatible update_plan uses `plan/step`. Other tool names are
 * not evidence of a plan. Malformed or partial arguments leave the prior snapshot intact.
 */
fun parseAgentTodos(toolName: String, arguments: String): List<SubagentTodo>? {
    val textKey = when (toolName) {
        "TodoWrite", "todo_write" -> "content"
        "update_plan" -> "step"
        else -> return null
    }
    return runCatching {
        val root = Json.parseToJsonElement(arguments) as? JsonObject ?: return null
        val items = root[if (textKey == "step") "plan" else "todos"] as? JsonArray ?: return null
        val todos = items.mapNotNull { (it as? JsonObject)?.authoredTodo(textKey) }
        todos.takeIf { it.size == items.size }
    }.getOrNull()
}

private fun JsonObject.authoredTodo(textKey: String): SubagentTodo? {
    val text = todoString(textKey) ?: return null
    val status = todoString("status") ?: return null
    val activeForm = if (containsKey("activeForm")) todoString("activeForm") ?: return null else ""
    return SubagentTodo(content = text, status = status, activeForm = activeForm)
}

private fun JsonObject.todoString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
