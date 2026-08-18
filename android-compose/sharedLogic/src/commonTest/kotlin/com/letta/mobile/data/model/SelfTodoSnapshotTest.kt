package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelfTodoSnapshotTest {
    private val activeTodos = listOf(
        SubagentTodo(content = "done", status = "completed"),
        SubagentTodo(content = "next", status = "in_progress"),
    )

    @Test
    fun `projects active progress`() {
        assertEquals(
            SelfTodoPlanState(completed = 1, total = 2),
            SelfTodoSnapshot(todos = activeTodos, lifecycleStatus = SubagentStatus.RUNNING).toActivePlanState(),
        )
    }

    @Test
    fun `terminal lifecycle suppresses incomplete plan`() {
        listOf(SubagentStatus.COMPLETED, SubagentStatus.FAILED, SubagentStatus.CANCELLED).forEach { status ->
            assertNull(SelfTodoSnapshot(todos = activeTodos, lifecycleStatus = status).toActivePlanState())
        }
    }
}
