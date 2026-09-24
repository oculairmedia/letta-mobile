package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** How one frame relates to the active lease's own run (letta-mobile-qygvv.8). */
internal enum class RunOwnership {
    /** The frame names a run the server says consumed THIS lease's `client_message_id`. */
    Own,

    /** The frame names a run the server says consumed only OTHER `client_message_id`s. */
    Foreign,

    /** No mapping for the frame's run (or no run id at all): legacy scope-only matching applies. */
    Unknown,
}

/**
 * letta-mobile-qygvv.8: binds each turn lease on ONE runtime key to its server run.
 *
 * App Server 0.32.17 reports `loop_status.client_message_ids_by_run_id` on `update_loop_status`:
 * the exact `client_message_id`s each recently observed run consumed. Frames used to be matched to
 * a lease by runtime scope alone, so an earlier queued turn that finally ran could stream into, and
 * complete, a later lease on the same conversation. With the mapping the engine can tell the
 * lease's own run from someone else's.
 *
 * The run -> client ids map is kept per runtime key and outlives a lease, so a late frame for a run
 * seen during the PREVIOUS lease is still recognised as foreign by the next one. With no mapping at
 * all (servers older than 0.32.17), or a lease that sent no `client_message_id` (approval
 * responses), every frame classifies [RunOwnership.Unknown] and the engine behaves as before.
 */
internal class TurnRunBinding {
    private val lock = SynchronizedObject()
    private val clientIdsByRunId = LinkedHashMap<String, Set<String>>()
    private val boundRunIds = mutableSetOf<String>()
    private var leaseToken: Long? = null
    private var clientMessageId: String? = null

    /** Starts binding for a new lease that sent [leaseClientMessageId] (null: nothing to bind). */
    fun beginLease(token: Long, leaseClientMessageId: String?): Unit = synchronized(lock) {
        if (leaseToken == token) return@synchronized
        leaseToken = token
        clientMessageId = leaseClientMessageId?.takeIf { it.isNotBlank() }
        boundRunIds.clear()
    }

    /**
     * Records the mapping [frame] carries. Returns the run ids that became bound to this lease's
     * `client_message_id` for the first time, in server order; the engine promotes the last one.
     */
    fun observe(frame: AppServerInboundFrame): List<String> = synchronized(lock) {
        val status = frame as? AppServerInboundFrame.UpdateLoopStatus ?: return@synchronized emptyList()
        val mapping = status.loopStatus.clientMessageIdsByRunId
        if (mapping.isEmpty()) return@synchronized emptyList()
        mapping.forEach { (runId, ids) -> remember(runId, ids.toSet()) }
        val own = clientMessageId ?: return@synchronized emptyList()
        mapping.entries
            .filter { (runId, ids) -> runId.isNotBlank() && own in ids && boundRunIds.add(runId) }
            .map { it.key }
    }

    /** Classifies the run [received] names against this lease's `client_message_id`. */
    fun ownershipOf(received: AppServerReceivedFrame): RunOwnership = synchronized(lock) {
        val own = clientMessageId ?: return@synchronized RunOwnership.Unknown
        val runId = received.boundRunIdOrNull() ?: return@synchronized RunOwnership.Unknown
        val ids = clientIdsByRunId[runId]
        when {
            ids.isNullOrEmpty() -> RunOwnership.Unknown
            own in ids -> RunOwnership.Own
            else -> RunOwnership.Foreign
        }
    }

    private fun remember(runId: String, ids: Set<String>) {
        if (runId.isBlank()) return
        clientIdsByRunId.remove(runId)
        clientIdsByRunId[runId] = ids
        while (clientIdsByRunId.size > RECENT_RUN_CAPACITY) {
            clientIdsByRunId.remove(clientIdsByRunId.keys.first())
        }
    }

    private companion object {
        const val RECENT_RUN_CAPACITY = 64
    }
}

/**
 * The run id a lease-affecting frame names: a stream delta's `run_id`, `turn_finished.run_id`, or
 * the first active run of a loop status (the mapper projects that one as the lease's Running run,
 * so a foreign active run must not reach the lease either). An idle loop status names no run.
 */
internal fun AppServerReceivedFrame.boundRunIdOrNull(): String? = when (val f = frame) {
    is AppServerInboundFrame.TurnFinished -> f.runId?.takeIf { it.isNotBlank() }
    is AppServerInboundFrame.UpdateLoopStatus -> f.loopStatus.activeRunIds.firstOrNull()?.takeIf { it.isNotBlank() }
    else -> frameRunIdOrNull()
}

/**
 * letta-mobile-qygvv.8: the engine's per-frame binding step for this slot's lease [leaseToken].
 * Promotes a run the server just bound to this lease's `client_message_id` (`turn.run_bound`) and
 * returns false for a frame whose run belongs to a different `client_message_id`
 * (`frame.foreign_run_dropped`): it must neither mutate nor complete this lease. The fanout still
 * delivers that frame to viewers; only this lease's collector skips it.
 */
internal fun TurnLeaseSlot.bindRunAndAccept(received: AppServerReceivedFrame, leaseToken: Long): Boolean {
    runBinding.observe(received.frame).lastOrNull()?.let { runId ->
        runIdGate.promote(runId, leaseToken)
        Telemetry.event(
            "AppServerTurnEngine", "turn.run_bound",
            "runId" to runId,
            "conversationId" to key.conversationId,
        )
    }
    if (runBinding.ownershipOf(received) != RunOwnership.Foreign) return true
    Telemetry.event(
        "AppServerTurnEngine", "frame.foreign_run_dropped",
        "runId" to (received.boundRunIdOrNull() ?: "<none>"),
        "frameType" to (received.frame.type ?: "<unknown>"),
        "conversationId" to key.conversationId,
    )
    return false
}
