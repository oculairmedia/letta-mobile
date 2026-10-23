package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset

/** The background task a task tool names, across toolsets (`task_id`, the older `bash_id` / `shell_id`). */
internal fun ToolCallFacts.taskId(): String? = TASK_ID_KEYS.firstNotNullOfOrNull(::string)

/** A call's result as its inset, when it has one worth reading. */
internal fun ToolCallFacts.resultInset(): ToolInset? = resultText()?.let(ToolInset::Text)

/**
 * The text of the first `<name>…</name>` element in [text] (letta-code's task results and
 * notifications are tagged this way), trimmed; null when absent or blank.
 */
internal fun tagged(text: String?, name: String): String? {
    val source = text ?: return null
    val open = source.indexOf("<$name>").takeIf { it >= 0 } ?: return null
    val start = open + name.length + 2
    val end = source.indexOf("</$name>", start).takeIf { it >= 0 } ?: source.length
    return source.substring(start, end).trim().takeIf { it.isNotEmpty() }
}

/** "task bash_3": how a step names the background task it acted on. */
internal fun taskLabel(id: String?): String = id?.let { "task $it" } ?: "task"

private val TASK_ID_KEYS = listOf("task_id", "bash_id", "shell_id", "id")
