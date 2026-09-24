package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review of PR #1661: an orphan abort never goes out without naming this lease's own run, so it
 * cannot abort another viewer's turn (an `abort_message` without a run id aborts whatever is active).
 */
class TurnReleaseAbortTest {
    private val aborter = OrphanedTurnAborter(
        abort = { _, _, _ -> error("shouldAbort only") },
        noteSettled = { _, _ -> },
    )

    @Test
    fun queuedLeaseNeverAbortsEvenWithAPromotedRunId() {
        // A run id promoted from frames that raced the queued ack belongs to the turn ahead.
        assertFalse(aborter.shouldAbort(facts(phase = TurnLeasePhase.Queued, runId = "run-ahead")))
        assertFalse(aborter.shouldAbort(facts(phase = TurnLeasePhase.Queued, runId = null)))
        assertFalse(aborter.shouldAbort(facts(phase = TurnLeasePhase.Queued, reason = "stream_error")))
    }

    @Test
    fun cancellationWithoutARunIdDoesNotAbort() {
        assertFalse(aborter.shouldAbort(facts(runId = null)))
    }

    @Test
    fun streamingLeaseWithItsRunIsAborted() {
        assertTrue(aborter.shouldAbort(facts(runId = "run-1")))
        assertTrue(aborter.shouldAbort(facts(runId = null, reason = "watchdog_timeout")))
    }

    private fun facts(
        phase: TurnLeasePhase = TurnLeasePhase.Streaming,
        runId: String? = "run-1",
        reason: String = "cancellation",
    ) = LeaseReleaseFacts(
        key = TurnRuntimeKey("agent-1", "conv-1"),
        leaseToken = 1L,
        releaseReason = reason,
        releaseCause = null,
        phase = phase,
        runId = runId,
        lastTerminal = null,
        lastTerminalSource = null,
        generationSuperseded = false,
        abortAlreadyRequested = false,
    )
}
