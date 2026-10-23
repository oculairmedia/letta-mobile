package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * letta-code's `TodoWrite` (and Codex's `update_plan`): "Updated todos · 2 of 4 done", opening to the
 * whole list as a checklist: done items struck through, the one in progress with a spinner, the rest `○`.
 */
object TodoWriteToolPresenter : ToolPresenter {
    override val id: String = "todo-write"

    override val matcher: ToolMatcher = ToolMatcher(listOf("TodoWrite", "todo_write", "update_plan", "write_todos"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val items = itemsOf(call)
        val done = items.takeIf { it.isNotEmpty() }?.let { "${it.count { item -> item.status == DONE }} of ${it.size} done" }
        return ToolStepLine(verb = ToolVerb.Updated, target = listOfNotNull("todos", done).joinToString(SEPARATOR), presenterId = id)
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
        val items = itemsOf(call)
        if (items.isEmpty()) return call.resultInset()
        return ToolInset.Checklist(
            items = items.map { ToolInset.Checklist.Item(it.text, done = it.status == DONE) },
            current = items.indexOfFirst { it.status == IN_PROGRESS }.takeIf { it >= 0 },
        )
    }

    /** The list's items in order: their text (`content`, Codex's `step`) and status. */
    private fun itemsOf(call: ToolCallFacts): List<TodoItem> {
        val list = LIST_KEYS.firstNotNullOfOrNull { call.arguments?.get(it) as? JsonArray } ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.let(::itemOf) }
    }

    private fun itemOf(item: JsonObject): TodoItem? {
        val text = TEXT_KEYS.firstNotNullOfOrNull { key -> (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }
            ?: return null
        val status = (item[STATUS] as? JsonPrimitive)?.content.orEmpty()
        return TodoItem(text, status)
    }

    /** One item of the list. */
    private class TodoItem(val text: String, val status: String)

    private const val STATUS = "status"
    private const val DONE = "completed"
    private const val IN_PROGRESS = "in_progress"
    private val LIST_KEYS = listOf("todos", "plan")
    private val TEXT_KEYS = listOf("content", "step", "description")
}
