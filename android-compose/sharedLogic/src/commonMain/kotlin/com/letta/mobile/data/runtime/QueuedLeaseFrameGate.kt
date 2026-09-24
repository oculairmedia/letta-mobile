package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame

/**
 * letta-mobile-qygvv.3 (PR #1661 review): keeps a QUEUED lease from adopting the turn ahead of it.
 *
 * While this lease's input waits in the server queue its own run does not exist yet, so every
 * run-bearing frame on the runtime belongs to another turn (another viewer's, or one parked on an
 * approval). Letting those frames through used to end the queued wait, promote the other turn's
 * run id, and complete this lease on the other turn's terminal, after which the server ran the
 * queued input with nobody observing it.
 *
 * While [QueuedInputTracker.isQueued]: `stream_delta`, `turn_finished` and `update_loop_status`
 * are skipped for this lease, and their run ids (plus any run id promoted from frames that raced
 * ahead of the `queued` ack) are remembered as foreign. After the `update_queue` dequeue, late
 * frames of a foreign run are still skipped, so the turn ahead's trailing `turn_finished` cannot
 * complete this lease either.
 *
 * Only the lease's collector calls [skip], so the foreign set needs no synchronization. Binding a
 * run to this input by `client_message_id` (0.32.17 `client_message_ids_by_run_id`) is left to the
 * turn-run binding; this gate only covers the "queued and run unknown" window.
 */
internal class QueuedLeaseFrameGate(private val queuedInput: QueuedInputTracker) {
    private val foreignRunIds = mutableSetOf<String>()

    /** Whether [received] must not reach this lease's boundary, run-id promotion or projection. */
    fun skip(received: AppServerReceivedFrame, leaseRunId: String?): Boolean {
        val frame = received.frame
        if (queuedInput.isQueued) {
            if (!frame.isRunScoped()) return false
            // Anything promoted before the queued ack came from the turn ahead.
            leaseRunId?.takeIf { it.isNotBlank() }?.let(foreignRunIds::add)
            received.runIdOrNull()?.let(foreignRunIds::add)
            return true
        }
        val runId = received.runIdOrNull() ?: return false
        return runId in foreignRunIds
    }

    private fun AppServerInboundFrame.isRunScoped(): Boolean =
        this is AppServerInboundFrame.StreamDelta ||
            this is AppServerInboundFrame.TurnFinished ||
            this is AppServerInboundFrame.UpdateLoopStatus

    private fun AppServerReceivedFrame.runIdOrNull(): String? = when (val frame = frame) {
        is AppServerInboundFrame.TurnFinished -> frame.runId?.takeIf { it.isNotBlank() }
        else -> frameRunIdOrNull()
    }
}
