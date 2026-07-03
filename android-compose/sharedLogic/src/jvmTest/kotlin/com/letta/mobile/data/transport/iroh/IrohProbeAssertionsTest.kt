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

    @Test
    fun `short final assistant fragment is reported as orphan fragment`() {
        val metrics = IrohProbeAssertions.metricsForFrames(
            turn = 1,
            frames = listOf(
                assistant(id = "assistant-1", content = "a"),
                done(),
            ),
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("orphan_fragment:turn1" in summary.violations)
    }


    @Test
    fun `skipped restart turn is ignored by probe assertions`() {
        val skipped = IrohProbeTurnMetrics(
            turn = 2,
            notes = listOf("restart-send skipped: --wrapper-restart-cmd not provided"),
            skipped = true,
        )

        val summary = IrohProbeAssertions.summarize(listOf(skipped))

        assertTrue(summary.ok)
        assertEquals(emptyList(), summary.violations)
        assertTrue(summary.turns.single().skipped)
        assertEquals(listOf("restart-send skipped: --wrapper-restart-cmd not provided"), summary.turns.single().notes)
    }

    @Test
    fun `idle send failures use named violation`() {
        val violation = IrohProbeAssertions.classifyIdleSendFailure("send failed")

        assertEquals("idle_send_failed:send failed", violation)
    }

    @Test
    fun `admin rpc unknown method is reported as method missing`() {
        val violation = IrohProbeAssertions.classifyAdminRpc(
            method = "message.list",
            success = false,
            resultIsArray = false,
            error = "Unknown method: message.list",
        )

        assertEquals("admin_rpc_method_missing:message.list", violation)
    }

    @Test
    fun `admin rpc non array result is reported as method missing`() {
        val violation = IrohProbeAssertions.classifyAdminRpc(
            method = "conversation.list",
            success = true,
            resultIsArray = false,
            error = null,
        )

        assertEquals("admin_rpc_method_missing:conversation.list", violation)
    }

    @Test
    fun `fresh conversation missing error is reported as bootstrap failure`() {
        val violation = IrohProbeAssertions.classifyConversationBootstrap("Conversation not found")

        assertEquals("conversation_bootstrap_failed", violation)
    }

    private fun assistant(id: String, content: String = "delta") = ServerFrame.AssistantMessage(
        id = id,
        ts = "2026-01-01T00:00:00Z",
        content = content,
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
