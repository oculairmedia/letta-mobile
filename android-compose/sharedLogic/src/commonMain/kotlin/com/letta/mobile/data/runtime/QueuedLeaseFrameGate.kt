package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

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
 * turn-run binding ([bindRun]), which decides "is this run mine": a [RunOwnership.Own] frame is
 * never skipped here, and a [RunOwnership.Foreign] one never reaches this gate. This gate only
 * decides "am I still queued" for the frames the binding cannot place.
 */
internal class QueuedLeaseFrameGate(private val queuedInput: QueuedInputTracker) {
    private val foreignRunIds = mutableSetOf<String>()

    /** Whether [received] must not reach this lease's boundary, run-id promotion or projection. */
    fun skip(received: AppServerReceivedFrame, leaseRunId: String?, ownership: RunOwnership): Boolean {
        if (ownership == RunOwnership.Own) return false
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

/**
 * letta-mobile-1n5py.1 (the second qygvv.9 race): opens once this lease's `input_accepted` is
 * resolved, whatever it said.
 *
 * The collector runs while the send coroutine still awaits the ack. A terminal of the turn ahead
 * that reaches the collector first used to complete this lease, because the input was not known
 * to be Queued yet. [awaitBefore] holds such a frame until the ack is resolved, so the Queued
 * gate ([QueuedLeaseFrameGate]) can recognise it as foreign.
 */
internal class InputAcknowledgementLatch {
    private val resolved = CompletableDeferred<Unit>()

    val isOpen: Boolean get() = resolved.isCompleted

    fun release() {
        resolved.complete(Unit)
    }

    /**
     * Holds a frame that could end the turn until the ack is resolved. Frames of this input's own
     * run never wait. A lost ack opens the latch after [PRE_ACK_TERMINAL_WAIT_MS], once, so the
     * turn's own terminal still ends it (the behaviour before acknowledgement existed).
     */
    suspend fun awaitBefore(received: AppServerReceivedFrame, ownership: RunOwnership) {
        if (isOpen || ownership == RunOwnership.Own || !received.canEndTurn()) return
        withTimeoutOrNull(PRE_ACK_TERMINAL_WAIT_MS.milliseconds) { resolved.await() } ?: release()
    }

    private fun AppServerReceivedFrame.canEndTurn(): Boolean = when (frame) {
        is AppServerInboundFrame.TurnFinished, is AppServerInboundFrame.UpdateLoopStatus -> true
        is AppServerInboundFrame.StreamDelta -> lifecycleStatusFromTerminal() != null
        else -> false
    }

    internal companion object {
        /** The ack precedes the terminal on the wire; this only bounds a lost ack. */
        const val PRE_ACK_TERMINAL_WAIT_MS = 250L
    }
}
