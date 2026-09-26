package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.RuntimeRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** letta-mobile-qygvv.2: the per-key decisions behind authoritative turn boundaries. */
class TurnBoundaryGateTest {
    private val gate = TurnBoundaryGate().also { it.beginLease(1) }

    @Test
    fun turnFinishedIsAuthoritativeOncePerTurnId() {
        assertEquals(TurnBoundaryDecision.ProjectAuthoritative, gate.decideFrame(run1.turnFinished(1), leaseRun = run1))
        val replay = assertIs<TurnBoundaryDecision.Drop>(gate.decideFrame(run1.turnFinished(1), leaseRun = run1))
        assertEquals("duplicate_turn_id", replay.reason)
    }

    @Test
    fun turnFinishedForASupersededRunIsIgnored() {
        val decision = assertIs<TurnBoundaryDecision.Drop>(gate.decideFrame(runOld.turnFinished(1), leaseRun = runNew))
        assertEquals("superseded_run", decision.reason)
    }

    @Test
    fun turnFinishedWithoutRunIdOrLeaseRunIsAccepted() {
        assertEquals(TurnBoundaryDecision.ProjectAuthoritative, gate.decideFrame(noRun.turnFinished(1), leaseRun = run1))
        assertEquals(TurnBoundaryDecision.ProjectAuthoritative, gate.decideFrame(run2.turnFinished(2)))
    }

    @Test
    fun terminalsForASettledRunCannotEndTheNextLease() {
        gate.noteSettled(run1.id)
        gate.beginLease(2)
        assertIs<TurnBoundaryDecision.Drop>(gate.decideFrame(run1.turnFinished(1)))
        assertIs<TurnBoundaryDecision.Drop>(gate.decideFrame(run1.stopDelta()))
        // Non-terminal frames of that run project as settled-run drafts and do not count as evidence.
        assertEquals(TurnBoundaryDecision.ProjectSettledRunDraft, gate.decideFrame(run1.assistantDelta()))
        assertEquals(TurnBoundaryDecision.Project, gate.decideFrame(TestLoopState.WaitingOnInput.frame()))
    }

    @Test
    fun idleLoopStatusBeforeEvidenceDoesNotComplete() {
        assertEquals(TurnBoundaryDecision.Project, gate.decideFrame(TestLoopState.WaitingOnInput.frame()))
    }

    @Test
    fun idleLoopStatusAfterEvidenceCompletes() {
        gate.decideFrame(run1.assistantDelta())
        val idle = assertIs<TurnBoundaryDecision.LoopIdle>(gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run1))
        assertEquals(RuntimeRunStatus.Completed, idle.status)
    }

    @Test
    fun idleLoopStatusAfterAbortCancels() {
        gate.decideFrame(run1.assistantDelta())
        gate.noteAbortRequested()
        val idle = assertIs<TurnBoundaryDecision.LoopIdle>(gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run1))
        assertEquals(RuntimeRunStatus.Cancelled, idle.status)
    }

    @Test
    fun approvalWaitAndActiveRunsNeverComplete() {
        gate.decideFrame(run1.stopDelta(TestStopReason.RequiresApproval))
        assertEquals(TurnBoundaryDecision.Project, gate.decideFrame(TestLoopState.WaitingOnApproval.frame(), leaseRun = run1))
        assertEquals(
            TurnBoundaryDecision.Project,
            gate.decideFrame(TestLoopState.WaitingOnInput.frame(activeRuns = listOf(run1)), leaseRun = run1),
        )
        assertEquals(
            TurnBoundaryDecision.Project,
            gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run1, approvalOutstanding = true),
        )
    }

    @Test
    fun idleBetweenApprovalContinuationRoundsDoesNotComplete() {
        // letta-mobile-qygvv.29: round 1 paused on requires_approval, its tool ran, the loop idled.
        gate.decideFrame(run1.assistantDelta())
        gate.decideFrame(run1.stopDelta(TestStopReason.RequiresApproval))
        assertEquals(TurnBoundaryDecision.Project, gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run1))
    }

    @Test
    fun idleAfterTheFinalRoundCompletes() {
        gate.decideFrame(run1.stopDelta(TestStopReason.RequiresApproval))
        gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run1)
        gate.decideFrame(run2.assistantDelta())
        gate.decideFrame(run2.stopDelta())
        val idle = assertIs<TurnBoundaryDecision.LoopIdle>(gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run2))
        assertEquals(RuntimeRunStatus.Completed, idle.status)
    }

    @Test
    fun idleBetweenRoundsAfterAbortCancels() {
        gate.decideFrame(run1.stopDelta(TestStopReason.RequiresApproval))
        gate.noteAbortRequested()
        val idle = assertIs<TurnBoundaryDecision.LoopIdle>(gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run1))
        assertEquals(RuntimeRunStatus.Cancelled, idle.status)
    }

    @Test
    fun newLeaseForgetsAContinuingRound() {
        gate.decideFrame(run1.stopDelta(TestStopReason.RequiresApproval))
        gate.beginLease(2)
        gate.decideFrame(run2.assistantDelta())
        assertIs<TurnBoundaryDecision.LoopIdle>(gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run2))
    }

    @Test
    fun newLeaseResetsEvidenceAndAbort() {
        gate.decideFrame(run1.assistantDelta())
        gate.noteAbortRequested()
        gate.beginLease(2)
        assertEquals(TurnBoundaryDecision.Project, gate.decideFrame(TestLoopState.WaitingOnInput.frame()))
        gate.decideFrame(run2.assistantDelta())
        val idle = assertIs<TurnBoundaryDecision.LoopIdle>(gate.decideFrame(TestLoopState.WaitingOnInput.frame(), leaseRun = run2))
        assertEquals(RuntimeRunStatus.Completed, idle.status)
    }

    @Test
    fun nonTerminalTurnFinishedProjectsWithoutConsumingTurnId() {
        assertEquals(
            TurnBoundaryDecision.Project,
            gate.decideFrame(run1.turnFinished(1, TestStopReason.RequiresApproval), leaseRun = run1),
        )
        // Terminal for the same turnId completes authoritatively and records the turnId for subsequent deduplication.
        assertEquals(TurnBoundaryDecision.ProjectAuthoritative, gate.decideFrame(run1.turnFinished(1), leaseRun = run1))
        val duplicate = assertIs<TurnBoundaryDecision.Drop>(gate.decideFrame(run1.turnFinished(1), leaseRun = run1))
        assertEquals("duplicate_turn_id", duplicate.reason)
    }

    @Test
    fun nonTerminalTurnFinishedForSettledRunDoesNotRecordTurnId() {
        gate.noteSettled(run1.id)
        gate.beginLease(2)
        val decision = assertIs<TurnBoundaryDecision.Drop>(
            gate.decideFrame(run1.turnFinished(1, TestStopReason.RequiresApproval)),
        )
        assertEquals("run_already_settled", decision.reason)
        // Since turn-1 was non-terminal, its turnId was not added to finishedTurnIds.
        assertEquals(TurnBoundaryDecision.ProjectAuthoritative, gate.decideFrame(run2.turnFinished(1), leaseRun = run2))
    }

    private companion object {
        val run1 = TestRun("run-1")
        val run2 = TestRun("run-2")
        val runOld = TestRun("run-old")
        val runNew = TestRun("run-new")
        val noRun = TestRun(null)
    }
}
