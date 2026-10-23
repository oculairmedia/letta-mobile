package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentTodoParserTest {
    @Test
    fun todoWriteAndUpdatePlanUseTheirOwnWireShapes() {
        val todos = parseAgentTodos("TodoWrite", """{"todos":[{"content":"Test","status":"in_progress","activeForm":"Testing"}]}""")!!
        assertEquals(listOf(SubagentTodo("Test", "in_progress", "Testing")), todos)
        assertEquals(SubagentTodoProgressWire(0, 1), todos.todoProgress())
        assertEquals(
            listOf(SubagentTodo("Test", "completed")),
            parseAgentTodos("update_plan", """{"explanation":"Verified","plan":[{"step":"Test","status":"completed"}]}"""),
        )
    }

    @Test
    fun absentMalformedAndUnverifiedToolsDoNotInventPlans() {
        assertNull(parseAgentTodos("Bash", """{"todos":[]}"""))
        assertNull(parseAgentTodos("write_todos", """{"todos":[]}"""))
        assertNull(parseAgentTodos("TodoWrite", """{"todos":[{"content":"partial"}]}"""))
        assertNull(parseAgentTodos("TodoWrite", """{"todos":[null]}"""))
        assertNull(parseAgentTodos("TodoWrite", """{"todos":["""))
        assertNull(parseAgentTodos("TodoWrite", """{"plan":[]}"""))
        assertEquals(emptyList(), parseAgentTodos("TodoWrite", """{"todos":[]}"""))
    }
}
