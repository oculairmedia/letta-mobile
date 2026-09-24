package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** What the engine does with one inbound frame at the turn boundary (letta-mobile-qygvv.2). */
internal sealed interface TurnBoundaryDecision {
    /** Project the frame as usual; a terminal it carries goes through the settle path. */
    data object Project : TurnBoundaryDecision

    /** Project the frame; a terminal lifecycle it carries is authoritative and completes the turn now. */
    data object ProjectAuthoritative : TurnBoundaryDecision

    /** Skip the frame: it closes a turn this runtime key has already settled. */
    data class Drop(val reason: String) : TurnBoundaryDecision

    /** The server's loop went idle after this turn produced evidence: complete with [status]. */
    data class LoopIdle(val status: RuntimeRunStatus) : TurnBoundaryDecision
}

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
    private val finishedTurnIds = RecentIds<String>(RECENT_CAPACITY)
    private val settledRunIds = RecentIds<String>(RECENT_CAPACITY)
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

    /** Whether an abort was already requested for the current lease (letta-mobile-qygvv.3). */
    fun isAbortRequested(): Boolean = synchronized(lock) { abortRequested }

    /** The active lease settled [runId]; later terminals for it belong to no live turn. */
    fun noteSettled(runId: String?) {
        if (!runId.isNullOrBlank()) {
            synchronized(lock) {
                settledRunIds.add(runId)
            }
        }
    }

    fun decide(
        received: AppServerReceivedFrame,
        leaseRunId: String?,
        approvalOutstanding: Boolean,
    ): TurnBoundaryDecision = synchronized(lock) {
        when (val frame = received.frame) {
            is AppServerInboundFrame.StreamDelta -> decideStreamDelta(received)
            is AppServerInboundFrame.TurnFinished -> decideTurnFinished(frame, leaseRunId)
            is AppServerInboundFrame.UpdateLoopStatus -> decideLoopStatus(frame, approvalOutstanding)
            else -> TurnBoundaryDecision.Project
        }
    }

    private fun decideStreamDelta(received: AppServerReceivedFrame): TurnBoundaryDecision {
        val runId = received.frameRunIdOrNull()
        val lateTerminal = runId != null &&
            runId in settledRunIds &&
            received.lifecycleStatusFromTerminal() != null
        if (lateTerminal) return TurnBoundaryDecision.Drop("terminal_delta_for_settled_run")
        evidenceSeen = true
        return TurnBoundaryDecision.Project
    }

    private fun decideTurnFinished(
        frame: AppServerInboundFrame.TurnFinished,
        leaseRunId: String?,
    ): TurnBoundaryDecision {
        if (frame.turnId in finishedTurnIds) return TurnBoundaryDecision.Drop("duplicate_turn_id")
        val runId = frame.runId?.takeIf { it.isNotBlank() }
        if (runId != null && runId in settledRunIds) {
            finishedTurnIds.add(frame.turnId)
            return TurnBoundaryDecision.Drop("run_already_settled")
        }
        if (runId != null && leaseRunId != null && runId != leaseRunId) {
            return TurnBoundaryDecision.Drop("superseded_run")
        }
        finishedTurnIds.add(frame.turnId)
        return TurnBoundaryDecision.ProjectAuthoritative
    }

    private fun decideLoopStatus(
        frame: AppServerInboundFrame.UpdateLoopStatus,
        approvalOutstanding: Boolean,
    ): TurnBoundaryDecision {
        val idle = frame.loopStatus.status == LOOP_WAITING_ON_INPUT &&
            frame.loopStatus.activeRunIds.isEmpty()
        if (!idle) return TurnBoundaryDecision.Project
        if (!evidenceSeen) return TurnBoundaryDecision.Project
        if (approvalOutstanding) return TurnBoundaryDecision.Project
        val status = if (abortRequested) RuntimeRunStatus.Cancelled else RuntimeRunStatus.Completed
        return TurnBoundaryDecision.LoopIdle(status)
    }

    /** Insertion-ordered, bounded set: the oldest id is evicted once [capacity] is exceeded. */
    private class RecentIds<T>(private val capacity: Int) {
        private val ids = LinkedHashSet<T>()

        operator fun contains(id: T): Boolean = id in ids

        fun add(id: T) {
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
