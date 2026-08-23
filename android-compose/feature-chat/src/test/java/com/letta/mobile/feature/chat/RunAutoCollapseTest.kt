package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import com.letta.mobile.feature.chat.coordination.assistantRunGroups
import com.letta.mobile.feature.chat.coordination.runIdsToCollapseOnTerminalReconciliation

/**
 * letta-mobile-ah1ng: terminal-run reconciliation selection. Collapse is
 * derived from per-run projected terminal state on every publication — not
 * from the global isStreaming edge and not restricted to the newest run.
 */
class RunAutoCollapseTest {

    @Test
    fun `terminal reconciliation selects terminal assistant run ids`() {
        val next = runIdsToCollapseOnTerminalReconciliation(
            messages = listOf(
                message(id = "user", role = "user", runId = null),
                message(id = "reasoning", runId = "run-1", isReasoning = true),
                message(id = "assistant", runId = "run-1"),
            ),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
            conversationHasActivePresence = false,
        )

        assertEquals(setOf("run-1"), next)
    }

    @Test
    fun `terminal reconciliation collapses every terminal run deterministically`() {
        val next = runIdsToCollapseOnTerminalReconciliation(
            messages = listOf(
                message(id = "older", runId = "run-1"),
                message(id = "newer", runId = "run-2"),
            ),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
            conversationHasActivePresence = false,
        )

        assertEquals(setOf("run-1", "run-2"), next)
    }

    @Test
    fun `active newest run stays open while older terminal runs collapse`() {
        val next = runIdsToCollapseOnTerminalReconciliation(
            messages = listOf(
                message(id = "old-assistant", runId = "run-old"),
                message(id = "live-reasoning", runId = "run-live", isPending = true, isReasoning = true),
            ),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
            conversationHasActivePresence = true,
        )

        assertEquals(setOf("run-old"), next)
    }

    @Test
    fun `newest settled run stays open while conversation presence is active`() {
        // Streaming presence scopes to the newest run even when no row is
        // currently pending (inter-round gap held by the transport latch).
        val next = runIdsToCollapseOnTerminalReconciliation(
            messages = listOf(message(id = "tail", runId = "run-tail")),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
            conversationHasActivePresence = true,
        )

        assertEquals(emptySet<String>(), next)
    }

    @Test
    fun `pending rows keep their run open regardless of presence`() {
        val next = runIdsToCollapseOnTerminalReconciliation(
            messages = listOf(
                message(id = "pending-reasoning", runId = "run-live", isPending = true, isReasoning = true),
            ),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
            conversationHasActivePresence = false,
        )

        assertEquals(emptySet<String>(), next)
    }

    @Test
    fun `already collapsed runs are not re-selected`() {
        val next = runIdsToCollapseOnTerminalReconciliation(
            messages = listOf(
                message(id = "collapsed-run", runId = "run-1"),
                message(id = "fresh-run", runId = "run-2"),
            ),
            collapsedRunIds = setOf("run-1"),
            autoCollapseSuppressedRunIds = emptySet(),
            conversationHasActivePresence = false,
        )

        // Only the new addition is returned; callers merge additively so the
        // manually/auto collapsed run-1 is never reopened.
        assertEquals(setOf("run-2"), next)
    }

    @Test
    fun `suppressed runs are never selected`() {
        val next = runIdsToCollapseOnTerminalReconciliation(
            messages = listOf(message(id = "assistant", runId = "run-1")),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = setOf("run-1"),
            conversationHasActivePresence = false,
        )

        assertEquals(emptySet<String>(), next)
    }

    @Test
    fun `blank run ids stay ungrouped`() {
        val next = runIdsToCollapseOnTerminalReconciliation(
            messages = listOf(
                message(id = "assistant-blank", runId = ""),
                message(id = "assistant-null", runId = null),
                message(id = "user", role = "user", runId = null),
            ),
            collapsedRunIds = emptySet(),
            autoCollapseSuppressedRunIds = emptySet(),
            conversationHasActivePresence = false,
        )

        assertEquals(emptySet<String>(), next)
    }

    @Test
    fun `groups preserve first-appearance order so newest is deterministic`() {
        val groups = assistantRunGroups(
            listOf(
                message(id = "a", runId = "run-1"),
                message(id = "b", runId = "run-2"),
                message(id = "c", runId = "run-1"),
            ),
        )

        assertEquals(listOf("run-1", "run-2"), groups.map { it.runId })
        assertEquals(listOf("a", "c"), groups.single { it.runId == "run-1" }.messages.map { it.id })
    }

    private fun message(
        id: String,
        role: String = "assistant",
        runId: String? = "run-1",
        isPending: Boolean = false,
        isReasoning: Boolean = false,
    ) = UiMessage(
        id = id,
        role = role,
        content = id,
        timestamp = "2026-05-02T12:00:00Z",
        runId = runId,
        isPending = isPending,
        isReasoning = isReasoning,
    )
}
