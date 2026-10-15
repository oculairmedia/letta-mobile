package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-by4vm.7: every (phase, event) pair of [TurnBoundaryGate]'s boundary facts. The table
 * is total (each pair is a legal move), so each pair is checked against its written-out result, and
 * each phase is checked against the three flags it replaced.
 */
class TurnBoundaryPhaseTest {

    private val awaiting = BoundaryPhase.AwaitingEvidence
    private val evidence = BoundaryPhase.EvidenceSeen
    private val continuation = BoundaryPhase.ContinuationPending
    private val abortedEarly = BoundaryPhase.AbortedAwaitingEvidence
    private val aborted = BoundaryPhase.AbortedWithEvidence

    /** Rows: from-phase. Columns, in [BoundaryEvent] order: LeaseBegan, Delta, RoundStopped, RoundStoppedForApproval, AbortRequested. */
    private val expected: Map<BoundaryPhase, List<BoundaryPhase>> = mapOf(
        awaiting to listOf(awaiting, evidence, evidence, continuation, abortedEarly),
        evidence to listOf(awaiting, evidence, evidence, continuation, aborted),
        continuation to listOf(awaiting, continuation, evidence, continuation, aborted),
        abortedEarly to listOf(awaiting, aborted, aborted, aborted, abortedEarly),
        aborted to listOf(awaiting, aborted, aborted, aborted, aborted),
    )

    @Test
    fun everyPhaseEventPairMovesToTheExpectedPhase() {
        assertEquals(BoundaryPhase.all.toSet(), expected.keys)
        var checked = 0
        BoundaryPhase.all.forEach { phase ->
            BoundaryEvent.entries.forEachIndexed { index, event ->
                assertEquals(expected.getValue(phase)[index], boundaryTransition(phase, event), "$phase + $event")
                checked += 1
            }
        }
        assertEquals(25, checked)
    }

    @Test
    fun eachPhaseCarriesTheFlagsItReplaced() {
        // (evidenceSeen, abortRequested, roundContinues)
        val flags = BoundaryPhase.all.associateWith { Triple(it.evidenceSeen, it.abortRequested, it.roundContinues) }
        assertEquals(
            mapOf(
                awaiting to Triple(false, false, false),
                evidence to Triple(true, false, false),
                continuation to Triple(true, false, true),
                abortedEarly to Triple(false, true, false),
                aborted to Triple(true, true, false),
            ),
            flags,
        )
    }

    @Test
    fun gateResetsItsPhaseForANewLeaseOnly() {
        val gate = TurnBoundaryGate()
        gate.beginLease(1)
        gate.noteAbortRequested()
        assertEquals(abortedEarly, gate.phase)

        gate.beginLease(1)
        assertEquals(abortedEarly, gate.phase, "the same lease beginning again keeps its facts")

        gate.beginLease(2)
        assertEquals(awaiting, gate.phase)
    }
}
