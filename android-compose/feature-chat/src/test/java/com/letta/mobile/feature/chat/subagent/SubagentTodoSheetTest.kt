package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentTodo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

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
