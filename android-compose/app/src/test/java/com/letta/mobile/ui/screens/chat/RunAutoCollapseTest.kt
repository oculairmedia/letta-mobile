package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class RunAutoCollapseTest {

    @Test
    fun `completion auto-collapse includes assistant run ids`() {
        val next = collapsedRunIdsAfterRunCompletion(
            messages = listOf(
                message(id = "user", role = "user", runId = null),
                message(id = "reasoning", runId = "run-1"),
                message(id = "assistant", runId = "run-1"),
            ),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
        )

        assertEquals(setOf("run-1"), next)
    }

    @Test
    fun `completion auto-collapse only adds the newest assistant run`() {
        val next = collapsedRunIdsAfterRunCompletion(
            messages = listOf(
                message(id = "older", runId = "run-1"),
                message(id = "newer", runId = "run-2"),
            ),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
        )

        assertEquals(setOf("run-2"), next)
    }

    @Test
    fun `completion auto-collapse preserves manually collapsed runs`() {
        val next = collapsedRunIdsAfterRunCompletion(
            messages = listOf(message(id = "assistant", runId = "run-2")),
            collapsedRunIds = setOf("run-1"),
            autoCollapseSuppressedRunIds = emptySet(),
        )

        assertEquals(setOf("run-1", "run-2"), next)
    }

    @Test
    fun `completion auto-collapse does not re-collapse manually expanded runs`() {
        val next = collapsedRunIdsAfterRunCompletion(
            messages = listOf(message(id = "assistant", runId = "run-1")),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = setOf("run-1"),
        )

        assertEquals(emptySet<String>(), next)
    }

    @Test
    fun `completion auto-collapse ignores user run ids and blank run ids`() {
        val next = collapsedRunIdsAfterRunCompletion(
            messages = listOf(
                message(id = "user", role = "user", runId = "run-user"),
                message(id = "assistant-blank", runId = ""),
                message(id = "assistant-null", runId = null),
            ),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
        )

        assertEquals(emptySet<String>(), next)
    }

    private fun message(
        id: String,
        role: String = "assistant",
        runId: String? = "run-1",
    ) = UiMessage(
        id = id,
        role = role,
        content = id,
        timestamp = "2026-05-02T12:00:00Z",
        runId = runId,
    )
}
