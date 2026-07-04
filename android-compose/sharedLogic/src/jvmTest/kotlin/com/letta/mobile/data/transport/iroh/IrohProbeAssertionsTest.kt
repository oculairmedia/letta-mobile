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
        assertEquals(listOf(5), metrics.assistantFinalTextLengths)
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
        assertEquals(listOf(1), metrics.assistantFinalTextLengths)
        assertTrue("orphan_fragment:turn1" in summary.violations)
    }



    @Test
    fun `orphan fragment assertion stays strict after terminal turn`() {
        val metrics = IrohProbeAssertions.metricsForFrames(
            turn = 1,
            frames = listOf(
                assistant(id = "assistant-1", content = "OK"),
                done(),
            ),
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertEquals(listOf(2), metrics.assistantFinalTextLengths)
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

    @Test
    fun `non monotonic event seq is reported`() {
        val metrics = sendTurn().copy(eventSeqs = listOf(1L, 2L, 2L, 3L))

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("turn1:event_seq_not_monotonic" in summary.violations)
    }

    @Test
    fun `strictly increasing event seq passes`() {
        val metrics = sendTurn().copy(eventSeqs = listOf(3L, 9L, 12L))

        assertTrue(IrohProbeAssertions.summarize(listOf(metrics)).ok)
        assertTrue(IrohProbeAssertions.isEventSeqMonotonic(emptyList()))
        assertTrue(IrohProbeAssertions.isEventSeqMonotonic(listOf(7L)))
        assertFalse(IrohProbeAssertions.isEventSeqMonotonic(listOf(7L, 5L)))
    }

    @Test
    fun `cross turn event seq reset on same connection is reported`() {
        assertEquals(
            "event_seq_reset_across_turns:9->1",
            IrohProbeAssertions.classifyCrossTurnEventSeq(listOf(7L, 8L, 9L), listOf(1L, 2L, 3L)),
        )
        assertEquals(
            "event_seq_reset_across_turns:9->9",
            IrohProbeAssertions.classifyCrossTurnEventSeq(listOf(9L), listOf(9L)),
        )
    }

    @Test
    fun `cross turn event seq continuity passes`() {
        assertEquals(null, IrohProbeAssertions.classifyCrossTurnEventSeq(listOf(7L, 8L, 9L), listOf(10L, 11L)))
        // Either side empty: nothing to compare (e.g. a turn that produced no frames
        // is already reported by the per-turn send-profile rules).
        assertEquals(null, IrohProbeAssertions.classifyCrossTurnEventSeq(emptyList(), listOf(1L)))
        assertEquals(null, IrohProbeAssertions.classifyCrossTurnEventSeq(listOf(9L), emptyList()))
    }

    @Test
    fun `untyped stream frames are reported`() {
        val metrics = sendTurn().copy(untypedFrameCount = 2)

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("turn1:untyped_frames_2" in summary.violations)
    }

    @Test
    fun `frames after terminal are reported`() {
        val metrics = sendTurn().copy(framesAfterTerminal = 1)

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("turn1:frames_after_terminal_1" in summary.violations)
    }

    @Test
    fun `scenario name prefixes violations`() {
        val metrics = sendTurn().copy(scenario = "duplicate-send", untypedFrameCount = 1)

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertTrue("duplicate-send:turn1:untyped_frames_1" in summary.violations)
    }

    @Test
    fun `clean cancel turn passes cancel profile`() {
        val metrics = cancelTurn(
            terminalStatus = "cancelled",
            terminalRunId = "run-42",
            activeRunId = "run-42",
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertTrue(summary.ok, "violations: ${summary.violations}")
    }

    @Test
    fun `cancel with completed terminal is reported`() {
        val metrics = cancelTurn(
            terminalStatus = "completed",
            terminalRunId = "run-42",
            activeRunId = "run-42",
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("cancel-midstream:turn1:cancel_terminal_status_completed" in summary.violations)
    }

    @Test
    fun `cancel with synthetic run id is reported`() {
        val metrics = cancelTurn(
            terminalStatus = "cancelled",
            terminalRunId = "cancelled-123",
            activeRunId = "run-42",
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("cancel-midstream:turn1:cancel_synthetic_run_id" in summary.violations)
    }

    @Test
    fun `cancel with mismatched run id is reported`() {
        val metrics = cancelTurn(
            terminalStatus = "cancelled",
            terminalRunId = "run-OTHER",
            activeRunId = "run-42",
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("cancel-midstream:turn1:cancel_run_id_mismatch" in summary.violations)
    }

    @Test
    fun `cancel with missing terminal run id is reported as mismatch`() {
        val metrics = cancelTurn(
            terminalStatus = "cancelled",
            terminalRunId = null,
            activeRunId = "run-42",
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("cancel-midstream:turn1:cancel_run_id_mismatch" in summary.violations)
    }

    @Test
    fun `dangling tool call at cancel is reported`() {
        val metrics = cancelTurn(
            terminalStatus = "cancelled",
            terminalRunId = "run-42",
            activeRunId = "run-42",
        ).copy(openToolCallIds = listOf("tc-1"))

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("cancel-midstream:turn1:dangling_tool_call_1" in summary.violations)
    }

    @Test
    fun `cancel timeout is reported without status noise`() {
        val metrics = cancelTurn(
            terminalStatus = null,
            terminalRunId = null,
            activeRunId = "run-42",
        ).copy(timedOut = true, turnDoneCount = 0)

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("cancel-midstream:turn1:timeout_missing_terminal" in summary.violations)
        assertTrue("cancel-midstream:turn1:turn_done_count_0" in summary.violations)
        assertFalse(summary.violations.any { it.contains("cancel_terminal_status") })
    }

    @Test
    fun `report profile skips send rules`() {
        val metrics = IrohProbeTurnMetrics(
            turn = 1,
            scenario = "hydrate-heavy",
            profile = IrohProbeAssertions.PROFILE_REPORT,
            dialMs = 5,
            eventSeqs = listOf(1L, 2L),
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertTrue(summary.ok, "violations: ${summary.violations}")
    }

    @Test
    fun `report profile still reports timeout`() {
        val metrics = IrohProbeTurnMetrics(
            turn = 1,
            scenario = "no-http",
            profile = IrohProbeAssertions.PROFILE_REPORT,
            timedOut = true,
        )

        val summary = IrohProbeAssertions.summarize(listOf(metrics))

        assertFalse(summary.ok)
        assertTrue("no-http:turn1:timeout" in summary.violations)
    }

    @Test
    fun `no http classifier flags any tcp connect`() {
        assertEquals(null, IrohProbeAssertions.classifyNoHttp(emptyList()))
        assertEquals(null, IrohProbeAssertions.classifyNoHttp(listOf(0, 0, 0)))
        assertEquals("no_http_tcp_connects:3", IrohProbeAssertions.classifyNoHttp(listOf(0, 3, 1)))
    }

    @Test
    fun `duplicate send classifier flags extra turns`() {
        assertEquals(null, IrohProbeAssertions.classifyDuplicateSend(1, "same-connection"))
        assertEquals(
            "duplicate_send_turns_2:same-connection",
            IrohProbeAssertions.classifyDuplicateSend(2, "same-connection"),
        )
        assertEquals(
            "duplicate_send_turns_2:after-redial",
            IrohProbeAssertions.classifyDuplicateSend(2, "after-redial"),
        )
    }

    @Test
    fun `hydrate heavy classifier checks completeness and budget`() {
        assertEquals(
            emptyList(),
            IrohProbeAssertions.classifyHydrateHeavy(seededCount = 24, listedCount = 24, wallMs = 900, budgetMs = 10_000),
        )
        assertEquals(
            listOf("hydrate_heavy_incomplete:20/24"),
            IrohProbeAssertions.classifyHydrateHeavy(seededCount = 24, listedCount = 20, wallMs = 900, budgetMs = 10_000),
        )
        assertEquals(
            listOf("hydrate_heavy_slow:12000ms>10000ms"),
            IrohProbeAssertions.classifyHydrateHeavy(seededCount = 24, listedCount = 24, wallMs = 12_000, budgetMs = 10_000),
        )
        assertEquals(
            listOf("hydrate_heavy_page_failed:page-3: boom"),
            IrohProbeAssertions.classifyHydrateHeavy(
                seededCount = 24,
                listedCount = 24,
                wallMs = 900,
                budgetMs = 10_000,
                pageFailures = listOf("page-3: boom"),
            ),
        )
    }

    private fun sendTurn(): IrohProbeTurnMetrics = IrohProbeAssertions.metricsForFrames(
        turn = 1,
        frames = listOf(assistant(id = "assistant-1"), done()),
    )

    private fun cancelTurn(
        terminalStatus: String?,
        terminalRunId: String?,
        activeRunId: String?,
    ): IrohProbeTurnMetrics = IrohProbeTurnMetrics(
        turn = 1,
        scenario = "cancel-midstream",
        profile = IrohProbeAssertions.PROFILE_CANCEL,
        dialMs = 5,
        firstFrameMs = 10,
        turnDoneCount = 1,
        terminalStatus = terminalStatus,
        terminalRunId = terminalRunId,
        activeRunId = activeRunId,
        eventSeqs = listOf(1L, 2L, 3L),
    )

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
