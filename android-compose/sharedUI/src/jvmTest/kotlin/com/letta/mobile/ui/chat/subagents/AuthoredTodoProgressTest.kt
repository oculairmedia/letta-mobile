package com.letta.mobile.ui.chat.subagents

import com.letta.mobile.data.model.todoProgress
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.SubagentChipObservation
import com.letta.mobile.data.subagents.SubagentChipSource
import com.letta.mobile.data.subagents.SubagentChipState
import com.letta.mobile.data.subagents.withNativeTodos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Same durable dispatch snapshot drives the entry, sheet math and production ring sweep. */
class AuthoredTodoProgressTest {
    private fun observation() = SubagentChipObservation(
        conversationId = "parent-conversation", agentId = "parent", toolCallId = "dispatch",
        state = SubagentChipState.RUNNING, source = SubagentChipSource.CONTROLLER_NATIVE,
    ).withNativeTodos(Json.parseToJsonElement("""
        {"tool_calls":[{"id":"todo-1","name":"TodoWrite","args":{
          "todos":[{"content":"Read","status":"completed","activeForm":"Reading"},
                   {"content":"Edit","status":"completed","activeForm":"Editing"},
                   {"content":"Test","status":"in_progress","activeForm":"Testing"}]
        }}]}
    """.trimIndent()).jsonObject)

    @Test
    fun registryEntrySheetAndRingAgreeAcrossEveryWorkerLifecycle() {
        for (state in SubagentChipState.entries) {
            val registry = DurableSubagentRegistry(clock = { 1_000L })
            val observed = observation().copy(state = state)
            registry.observe(observed)
            val record = registry.replaySnapshot("parent-conversation").single()
            val entry = record.toEntry()
            val todos = requireNotNull(record.todos)
            val sheet = subagentTodoSheetStateFrom(Result.success(todos)) as SubagentTodoSheetState.Content
            val ring = entry.toActiveSubagent()
            assertEquals(todos.todoProgress(), entry.todoProgress)
            assertEquals(2, sheet.progress.completed)
            assertEquals(3, sheet.progress.total)
            assertEquals(sheet.progress.fraction, ring.ringFraction)
            assertEquals(sheet.progress.fraction * COMPLETED_SWEEP, ring.todoSweep())
            assertTrue(ring.hasDeterminateProgress)
            assertEquals(state.isTerminal, ring.isTerminal)
        }
    }

    @Test
    fun completedTodosKeepRunningWorkerActiveAndUnknownPlansStayUnknownAfterExit() {
        val plan = observation()
        val registry = DurableSubagentRegistry(clock = { 1_000L })
        registry.observe(plan.copy(todos = plan.todos!!.map { it.copy(status = "completed") }))
        val completeTodos = registry.replaySnapshot("parent-conversation").single().toEntry().toActiveSubagent()
        assertEquals(1f, completeTodos.ringFraction)
        assertTrue(completeTodos.isActive)
        assertEquals(RingState.RUNNING, completeTodos.ringState(1_000L))

        for (todos in listOf<List<SubagentTodo>?>(null, emptyList())) {
            for (state in listOf(SubagentChipState.COMPLETED, SubagentChipState.FAILED)) {
                val unknownRegistry = DurableSubagentRegistry(clock = { 1_000L })
                unknownRegistry.observe(plan.copy(state = state, todos = todos))
                val record = unknownRegistry.replaySnapshot("parent-conversation").single()
                val ring = record.toEntry().toActiveSubagent()
                assertFalse(ring.hasDeterminateProgress)
                assertEquals(0f, ring.ringFraction)
                assertEquals(SLIVER_SWEEP, ring.todoSweep())
                assertEquals(SubagentTodoSheetState.Empty, subagentTodoSheetStateFrom(Result.success(record.todos.orEmpty())))
                assertEquals(if (state == SubagentChipState.COMPLETED) RingState.SUCCESS else RingState.ERROR, ring.ringState(1_000L))
            }
        }
    }
}
