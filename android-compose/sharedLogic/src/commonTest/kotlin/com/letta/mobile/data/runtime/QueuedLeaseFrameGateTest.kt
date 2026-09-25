package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.8 x qygvv.3: the run binding decides "is this run mine", the queued gate
 * decides "am I still queued" only for frames the binding cannot place.
 */
class QueuedLeaseFrameGateTest {
    @Test
    fun queuedLeaseSkipsAnUnplacedRun() {
        val gate = queuedGate()

        assertTrue(gate.skip(aheadRun.assistantDelta().onStream(), leaseRunId = null, RunOwnership.Unknown))
    }

    @Test
    fun queuedLeaseLetsItsOwnBoundRunThrough() {
        val gate = queuedGate()

        assertFalse(gate.skip(ownRun.assistantDelta().onStream(), leaseRunId = null, RunOwnership.Own))
    }

    @Test
    fun ownRunIsNotMistakenForTheTurnAheadAfterDequeue() {
        val queuedInput = QueuedInputTracker(CLIENT_MESSAGE_ID).apply { markQueued() }
        val gate = QueuedLeaseFrameGate(queuedInput)
        // A frame the binding cannot place while queued marks the promoted run as foreign ...
        gate.skip(aheadRun.assistantDelta().onStream(), leaseRunId = ownRun.id, RunOwnership.Unknown)
        queuedInput.markStarted()

        // ... but the binding's proof of ownership wins over that guess.
        assertFalse(gate.skip(ownRun.assistantDelta().onStream(), leaseRunId = ownRun.id, RunOwnership.Own))
        assertTrue(gate.skip(aheadRun.turnFinished(1).onStream(), leaseRunId = ownRun.id, RunOwnership.Unknown))
    }

    private fun queuedGate() =
        QueuedLeaseFrameGate(QueuedInputTracker(CLIENT_MESSAGE_ID).apply { markQueued() })

    private companion object {
        const val CLIENT_MESSAGE_ID = "local-1"
        val aheadRun = TestRun("run-ahead")
        val ownRun = TestRun("run-own")
    }
}
