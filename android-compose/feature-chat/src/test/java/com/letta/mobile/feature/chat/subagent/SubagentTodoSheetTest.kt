package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentTodo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
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
