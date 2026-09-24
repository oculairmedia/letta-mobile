package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry
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
        if (runId != null && leaseRunId != null) {
            if (runId != leaseRunId) return TurnBoundaryDecision.Drop("superseded_run")
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

internal fun AppServerReceivedFrame.eventSeqOrNull(): Long? =
    when (val f = frame) {
        is AppServerInboundFrame.StreamDelta -> f.eventSeq
        is AppServerInboundFrame.UpdateLoopStatus -> f.eventSeq
        is AppServerInboundFrame.TurnFinished -> f.eventSeq
        is AppServerInboundFrame.UpdateDeviceStatus -> f.eventSeq
        is AppServerInboundFrame.UpdateQueue -> f.eventSeq
        is AppServerInboundFrame.UpdateSubagentState -> f.eventSeq
        else -> null
    }

/**
 * letta-mobile-qygvv.2: `turn_finished` and an idle loop status after evidence are the
 * server's own turn boundaries. Returns null when the frame closes a turn this key already
 * settled (a duplicate `turn_id`, a superseded or settled run) and must be skipped.
 */
internal fun TurnBoundaryGate.decideBoundary(
    received: AppServerReceivedFrame,
    leaseRunId: String?,
    conversationId: String,
    hasOutstandingApproval: Boolean,
): TurnBoundaryDecision? {
    val decision = decide(
        received = received,
        leaseRunId = leaseRunId,
        approvalOutstanding = hasOutstandingApproval,
    )
    if (decision !is TurnBoundaryDecision.Drop) return decision
    Telemetry.event(
        "AppServerTurnEngine", "terminal.boundary_dropped",
        "frameType" to (received.frame.type ?: "<unknown>"),
        "reason" to decision.reason,
        "conversationId" to conversationId,
        "eventSeq" to received.eventSeqOrNull(),
    )
    return null
}

internal fun TurnCommand.loopIdleTerminal(status: RuntimeRunStatus, leaseRunId: String?): RuntimeEventDraft {
    val runId = leaseRunId?.takeIf { it.isNotBlank() }?.let(::RunId)
    return when (status) {
        RuntimeRunStatus.Cancelled -> cancelledDraft("App Server loop idle after abort").copy(runId = runId)
        else -> completedDraft(runId)
    }
}
