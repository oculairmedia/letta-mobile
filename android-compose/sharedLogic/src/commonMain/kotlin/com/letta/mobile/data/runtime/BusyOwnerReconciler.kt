package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Job

/**
 * letta-mobile-c4igq.3 / lgns8.22.2 / qygvv.3: busy-path recovery for ONE runtime key. Clears a
 * dead owner ONLY on the server's own evidence ([TurnOwnerLivenessProbe]), and only by cancelling
 * and joining that owner's job, never by force-unlocking. Preparing/Starting/Queued leases are
 * locally alive and are never probed.
 */
internal class BusyOwnerReconciler(
    private val probe: TurnOwnerLivenessProbe,
    private val otherBusyKeys: (TurnRuntimeKey) -> List<TurnRuntimeKey>,
) {
    /**
     * Installs [lease] in [slot]. A busy slot is taken over only when [reconcile] proves its
     * owner dead (the server's loop is idle); otherwise the turn is rejected with [rejectSameKey].
     */
    suspend fun acquireOrReject(slot: TurnLeaseSlot, lease: TurnLease) {
        if (slot.casLease(null, lease)) return
        if (reconcile(slot) && slot.casLease(null, lease)) return
        rejectSameKey(slot, lease)
    }

    /** True when a dead owner was released and a successor may now acquire [slot]. */
    private suspend fun reconcile(slot: TurnLeaseSlot): Boolean {
        val owner = slot.lease ?: return false
        if (owner.isEnding || owner.isLocallyAliveWithoutRun) return false
        val scope = slot.runtimeScope ?: AppServerRuntimeScope(slot.key.agentId, slot.key.conversationId)
        val liveness = probe.probe(scope)
        Telemetry.event(
            "AppServerTurnEngine", "activeTurn.reconciled",
            "key" to slot.key.toString(),
            "leaseToken" to owner.token,
            "ownerPhase" to owner.phase.name,
            "liveness" to liveness.name,
        )
        if (liveness != OwnerLiveness.Dead) return false
        return releaseDeadOwner(slot, owner)
    }

    /**
     * SENSING (c, letta-mobile-8xxzv): the LEGITIMATE busy, a second turn for the SAME
     * {agent, conversation} runtime. letta-code permits at most one active turn per runtime, so
     * this rejection of [rejected] preserves the server contract rather than serializing the app.
     */
    private fun rejectSameKey(slot: TurnLeaseSlot, rejected: TurnLease): Nothing {
        val holder = slot.lease
        Telemetry.event(
            "AppServerTurnEngine", "activeTurn.rejectedSameKey",
            "key" to slot.key.toString(),
            "rejectedLeaseToken" to rejected.token,
            "ownerLeaseToken" to holder?.token,
            "ownerPhase" to holder?.phase?.name,
            "ownerRunId" to (holder?.runId ?: "<none>"),
            "ownerHeldForMs" to holder?.acquiredAtMs?.let { rejected.acquiredAtMs - it },
            "otherBusyKeys" to otherBusyKeys(slot.key).joinToString(",") { it.toString() },
            level = Telemetry.Level.WARN,
        )
        throw IllegalStateException(
            "An App Server turn is already active for ${rejected.agentId}/${rejected.conversationId}.",
        )
    }

    private suspend fun releaseDeadOwner(slot: TurnLeaseSlot, probed: TurnLease): Boolean {
        // The owner's lease object may have changed during the probe (the replayed frames reach
        // its collector too), so retire whatever lease still carries the probed token. If the
        // owner ended on its own meanwhile, the slot is free for the caller to retry.
        val owner = retireLease(slot, probed.token) ?: return slot.lease?.token != probed.token
        // Cancel and join the owning structured scope before admitting a successor. The cause
        // tells the owner's release path there is no server turn left to abort.
        cancelOwnerScope(owner.ownerJob)
        // The owner's finally may already have cleared the retiring lease via token match.
        clearSlotOwner(slot, owner)
        // SENSING (b, letta-mobile-8xxzv): this lease ended via the reconciler, NOT via a
        // terminal frame. Scoped to ONE key so it also proves it never reaches across runtimes.
        Telemetry.event(
            "AppServerTurnEngine", "activeTurn.releasedByReconciler",
            "key" to slot.key.toString(),
            "leaseToken" to owner.token,
            "runId" to (owner.runId ?: "<none>"),
            "reason" to "loop_waiting_on_input",
            "lastTerminal" to (owner.lastTerminal ?: "<none>"),
            "otherBusyKeys" to otherBusyKeys(slot.key).joinToString(",") { it.toString() },
            level = Telemetry.Level.WARN,
        )
        return slot.lease?.token != owner.token
    }

    private suspend fun cancelOwnerScope(job: Job?) {
        if (job == null) return
        job.cancel(DeadOwnerReleasedCancellation())
        runCatching { job.join() }
    }

    private fun clearSlotOwner(slot: TurnLeaseSlot, owner: TurnLease) {
        slot.updateLease { cur -> if (cur?.token == owner.token) null else cur }
        slot.updateOwner { telemetry -> telemetry?.takeUnless { it.isFor(owner) } }
    }

    /** Moves the live lease with [token] to Retiring; null when no such live lease remains. */
    private fun retireLease(slot: TurnLeaseSlot, token: Long): TurnLease? {
        while (true) {
            val current = slot.lease ?: return null
            if (current.token != token) return null
            if (current.isEnding) return null
            if (slot.casLease(current, current.copy(phase = TurnLeasePhase.Retiring))) return current
        }
    }
}
