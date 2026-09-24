package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerStopReason
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** What the engine does with one inbound frame at the turn boundary (letta-mobile-qygvv.2). */
internal sealed interface TurnBoundaryDecision {
    val isAuthoritative: Boolean get() = false
    val allowsRunPromotion: Boolean get() = true

    /** Project the frame as usual; a terminal it carries goes through the settle path. */
    data object Project : TurnBoundaryDecision

    /** Project a non-terminal frame from a settled run: do not treat as evidence, do not promote run ID. */
    data object ProjectSettledRunDraft : TurnBoundaryDecision {
        override val allowsRunPromotion: Boolean get() = false
    }

    /** Project the frame; a terminal lifecycle it carries is authoritative and completes the turn now. */
    data object ProjectAuthoritative : TurnBoundaryDecision {
        override val isAuthoritative: Boolean get() = true
    }

    /** Skip the frame: it closes a turn this runtime key has already settled. */
    data class Drop(val reason: String) : TurnBoundaryDecision

    /** The server's loop went idle after this turn produced evidence: complete with [status]. */
    data class LoopIdle(val status: RuntimeRunStatus) : TurnBoundaryDecision {
        override val isAuthoritative: Boolean get() = true
    }
}

/** One inbound frame plus the lease facts the boundary decision reads (letta-mobile-qygvv.2). */
internal data class TurnBoundaryInput(
    val received: AppServerReceivedFrame,
    val leaseRunId: String?,
    val approvalOutstanding: Boolean,
)

/**
 * letta-mobile-qygvv.2: authoritative turn boundaries for ONE runtime key, mirroring the reference
 * SDK's remote turn coordinator.
 *
 * Precedence, highest first:
 *  1. `turn_finished` (App Server 0.32+) completes the turn immediately. It is deduped by `turn_id`
 *     and ignored when it names a run other than the lease's promoted run.
 *  2. `update_loop_status` WAITING_ON_INPUT with no active runs is the completion fallback, but only
 *     after this lease has seen evidence (any stream delta), never while an approval is outstanding,
 *     and never for WAITING_ON_APPROVAL (that is the approval continuation boundary).
 *  3. The terminal `stop_reason` delta with its settle window, for servers without `turn_finished`.
 *
 * Settled run ids and finished turn ids outlive a lease so a late or replayed terminal for a turn
 * that already ended cannot end the NEXT turn on the same key.
 */
internal class TurnBoundaryGate {
    private val lock = SynchronizedObject()
    private val finishedTurnIds = RecentIds(RECENT_CAPACITY)
    private val settledRunIds = RecentIds(RECENT_CAPACITY)
    private var leaseToken: Long? = null
    private var evidenceSeen = false
    private var abortRequested = false

    /** Reset per-lease state when a new turn lease starts collecting. */
    fun beginLease(token: Long): Unit = synchronized(lock) {
        if (leaseToken == token) return@synchronized
        leaseToken = token
        evidenceSeen = false
        abortRequested = false
    }

    /** An `abort_message` was sent for this key: a later idle loop status reads as Cancelled. */
    fun noteAbortRequested(): Unit = synchronized(lock) { abortRequested = true }

    /** The active lease settled [runId]; later terminals for it belong to no live turn. */
    fun noteSettled(runId: String?): Unit = synchronized(lock) {
        runId?.takeIf { it.isNotBlank() }?.let(settledRunIds::add)
        Unit
    }

    fun decide(input: TurnBoundaryInput): TurnBoundaryDecision = synchronized(lock) {
        when (val frame = input.received.frame) {
            is AppServerInboundFrame.StreamDelta -> decideStreamDelta(input.received)
            is AppServerInboundFrame.TurnFinished -> decideTurnFinished(frame, input)
            is AppServerInboundFrame.UpdateLoopStatus -> decideLoopStatus(frame, input)
            else -> TurnBoundaryDecision.Project
        }
    }

    private fun decideStreamDelta(received: AppServerReceivedFrame): TurnBoundaryDecision {
        val runId = received.frameRunIdOrNull()
        if (runId != null && runId in settledRunIds) {
            if (received.lifecycleStatusFromTerminal() != null) {
                return TurnBoundaryDecision.Drop("terminal_delta_for_settled_run")
            }
            return TurnBoundaryDecision.ProjectSettledRunDraft
        }
        evidenceSeen = true
        return TurnBoundaryDecision.Project
    }

    private fun decideTurnFinished(
        frame: AppServerInboundFrame.TurnFinished,
        input: TurnBoundaryInput,
    ): TurnBoundaryDecision {
        val leaseRunId = input.leaseRunId
        if (frame.turnId in finishedTurnIds) return TurnBoundaryDecision.Drop("duplicate_turn_id")
        val terminal = AppServerStopReason.isTerminal(frame.stopReason)
        val runId = frame.runId?.takeIf { it.isNotBlank() }
        if (runId != null) {
            if (runId in settledRunIds) {
                if (terminal) finishedTurnIds.add(frame.turnId)
                return TurnBoundaryDecision.Drop("run_already_settled")
            }
            if (leaseRunId != null && runId != leaseRunId) {
                return TurnBoundaryDecision.Drop("superseded_run")
            }
        }
        if (!terminal) return TurnBoundaryDecision.Project
        finishedTurnIds.add(frame.turnId)
        return TurnBoundaryDecision.ProjectAuthoritative
    }

    private fun decideLoopStatus(
        frame: AppServerInboundFrame.UpdateLoopStatus,
        input: TurnBoundaryInput,
    ): TurnBoundaryDecision {
        if (!evidenceSeen || input.approvalOutstanding) return TurnBoundaryDecision.Project
        if (frame.loopStatus.status != LOOP_WAITING_ON_INPUT) return TurnBoundaryDecision.Project
        if (frame.loopStatus.activeRunIds.isNotEmpty()) return TurnBoundaryDecision.Project
        val status = if (abortRequested) RuntimeRunStatus.Cancelled else RuntimeRunStatus.Completed
        return TurnBoundaryDecision.LoopIdle(status)
    }

    /** Insertion-ordered, bounded set: the oldest id is evicted once [capacity] is exceeded. */
    private class RecentIds(private val capacity: Int) {
        private val ids = LinkedHashSet<String>()

        operator fun contains(id: String): Boolean = id in ids

        fun add(id: String) {
            ids.remove(id)
            ids.add(id)
            if (ids.size > capacity) ids.remove(ids.first())
        }
    }

    companion object {
        const val LOOP_WAITING_ON_INPUT: String = "WAITING_ON_INPUT"
        private const val RECENT_CAPACITY = 32
    }
}
