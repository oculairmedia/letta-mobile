package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrohProbeAssertionsTest {
    @Test
    fun `single clean turn passes probe assertions`() {
        val metrics = IrohProbeAssertions.metricsForFrames(
            turn = 1,
            frames = listOf(
                assistant(id = "assistant-1"),
                assistant(id = "assistant-1"),
                reasoning(id = "reasoning-1"),
                done(),
            ),
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertTrue(summary.ok)
        assertEquals(emptyList(), summary.violations)
        assertEquals(2, metrics.assistantDeltaCount)
        assertEquals(listOf("assistant-1"), metrics.assistantMessageIds)
        assertEquals(1, metrics.reasoningRowEstimate)
    }

    @Test
    fun `duplicate assistant ids are reported as duplicate response violation`() {
        val metrics = IrohProbeAssertions.metricsForFrames(
            turn = 1,
            frames = listOf(
                assistant(id = "assistant-1"),
                assistant(id = "assistant-2"),
                done(),
            ),
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("turn1:assistant_message_ids_2" in summary.violations)
    }

    @Test
    fun `per token reasoning ids are reported as token splinter violation`() {
        val metrics = IrohProbeAssertions.metricsForFrames(
            turn = 1,
            frames = listOf(
                assistant(id = "assistant-1"),
                reasoning(id = "reasoning-token-1"),
                reasoning(id = "reasoning-token-2"),
                reasoning(id = "reasoning-token-3"),
                done(),
            ),
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertEquals(3, metrics.reasoningRowEstimate)
        assertTrue("turn1:reasoning_message_ids_3" in summary.violations)
    }

    @Test
    fun `missing terminal frame is reported`() {
        val metrics = IrohProbeAssertions.metricsForFrames(
            turn = 1,
            frames = listOf(assistant(id = "assistant-1")),
            timedOut = true,
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("turn1:timeout_missing_terminal" in summary.violations)
        assertTrue("turn1:turn_done_count_0" in summary.violations)
    }

    private fun assistant(id: String) = ServerFrame.AssistantMessage(
        id = id,
        ts = "2026-01-01T00:00:00Z",
        content = "delta",
    )

    private fun reasoning(id: String) = ServerFrame.ReasoningMessage(
        id = id,
        ts = "2026-01-01T00:00:00Z",
        agentId = "agent-1",
        conversationId = "probe-conv-1",
        turnId = "turn-1",
        runId = "run-1",
        reasoning = "token",
    )

    private fun done() = ServerFrame.TurnDone(
        id = "done-1",
        ts = "2026-01-01T00:00:00Z",
        turnId = "turn-1",
        runId = "run-1",
        status = "completed",
    )
}
