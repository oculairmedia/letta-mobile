package com.letta.mobile.ui.chat.subagents

import com.letta.mobile.data.model.SubagentTodo
import kotlin.test.assertEquals
import kotlin.test.Test
class SubagentTodoSheetTest {

    @Test
    fun `blank todo text falls back to untitled label`() {
        val item = SubagentTodo(
            content = "",
            status = "pending",
            activeForm = "",
        ).toItem()

        assertEquals("Untitled todo", item.label)
        assertEquals(SubagentTodoStatus.PENDING, item.status)
    }
}
