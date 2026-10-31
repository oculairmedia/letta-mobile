package com.letta.mobile.ui.chat.subagents

import com.letta.mobile.data.model.SubagentTodo
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    // A round trip that never answers, and ignores cancellation, still lets the sheet stop spinning.
    @Test
    fun `a load that never answers fails at the deadline`() = runBlocking<Unit> {
        val never = CompletableDeferred<Result<List<SubagentTodo>>>()
        val result = loadWithin(50.milliseconds) { withContext(NonCancellable) { never.await() } }
        assertTrue(result.isFailure)
        assertTrue(subagentTodoSheetStateFrom(result) is SubagentTodoSheetState.Error)
        never.complete(Result.success(emptyList()))
    }

    @Test
    fun `a load that answers in time is passed through`() = runBlocking<Unit> {
        val result = loadWithin(1000.milliseconds) { Result.success(emptyList<SubagentTodo>()) }
        assertEquals(SubagentTodoSheetState.Empty, subagentTodoSheetStateFrom(result))
    }
}
