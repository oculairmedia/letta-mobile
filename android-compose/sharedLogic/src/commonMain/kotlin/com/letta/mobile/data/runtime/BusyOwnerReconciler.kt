package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/** What the App Server's own loop state says about a busy lease's owner. */
internal enum class OwnerLiveness { Dead, Alive, Inconclusive }

/**
 * letta-mobile-qygvv.3: protocol-native liveness for the owner of a busy runtime key.
 *
 * Replaces the admin_rpc `run.get` / `run.list` probe, which timed out on the production
 * controller (`reconcileLivenessTimedOut`) and so never freed a wedged lease. Instead this asks
 * the App Server itself: `sync{recover_approvals, force_device_status}` makes it replay the
 * runtime's current state, and the replayed `update_loop_status` decides. WAITING_ON_INPUT with
 * no active runs means no server turn is running, so the lease's owner is dead. Anything else
 * (a run in flight, an approval pending) keeps the owner alive.
 *
 * The probe listens through a PASSIVE router subscription: it never takes delivery of the
 * control requests the sync replays, so it cannot steal an approval from the live owner.
 */
internal class TurnOwnerLivenessProbe(
    private val client: AppServerClient,
    private val eventRouter: AppServerRuntimeEventRouter?,
    private val requestIdFactory: () -> String,
    private val timeoutMs: Long = PROBE_TIMEOUT_MS,
) {
    suspend fun probe(scope: AppServerRuntimeScope): OwnerLiveness {
        val (subscriberId, events) = observe(scope)
        return try {
            withTimeout(timeoutMs.milliseconds) { syncAndReadLoopStatus(scope, events) }
        } catch (timeout: TimeoutCancellationException) {
            Telemetry.event(
                "AppServerTurnEngine", "activeTurn.reconcileSyncTimedOut",
                "key" to "${scope.agentId}/${scope.conversationId}",
                "timeoutMs" to timeoutMs,
                level = Telemetry.Level.WARN,
            )
            OwnerLiveness.Inconclusive
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Telemetry.error(
                "AppServerTurnEngine", "activeTurn.reconcileSyncFailed", error,
                "key" to "${scope.agentId}/${scope.conversationId}",
            )
            OwnerLiveness.Inconclusive
        } finally {
            subscriberId?.let { eventRouter?.unsubscribe(it) }
        }
    }

    private suspend fun syncAndReadLoopStatus(
        scope: AppServerRuntimeScope,
        events: Flow<AppServerReceivedFrame>,
    ): OwnerLiveness = coroutineScope {
        // Listening starts before the sync goes out: the replay can arrive before its response.
        val loopStatus = async(start = CoroutineStart.UNDISPATCHED) {
            events.first { it.isLoopStatusFor(scope) }.frame as AppServerInboundFrame.UpdateLoopStatus
        }
        val response = client.sync(
            AppServerCommand.Sync(
                runtime = scope,
                requestId = requestIdFactory(),
                recoverApprovals = true,
                forceDeviceStatus = true,
            ),
        )
        if (!response.success) {
            loopStatus.cancel()
            return@coroutineScope OwnerLiveness.Inconclusive
        }
        val status = loopStatus.await().loopStatus
        val idle = status.status == TurnBoundaryGate.LOOP_WAITING_ON_INPUT && status.activeRunIds.isEmpty()
        if (idle) OwnerLiveness.Dead else OwnerLiveness.Alive
    }

    private fun observe(scope: AppServerRuntimeScope): Pair<String?, Flow<AppServerReceivedFrame>> {
        val router = eventRouter ?: return null to client.events
        val (id, flow) = router.observe(AgentId(scope.agentId), ConversationId(scope.conversationId))
        return id to flow
    }

    private fun AppServerReceivedFrame.isLoopStatusFor(scope: AppServerRuntimeScope): Boolean {
        val status = frame as? AppServerInboundFrame.UpdateLoopStatus ?: return false
        return status.runtime.agentId == scope.agentId && status.runtime.conversationId == scope.conversationId
    }

    companion object {
        const val PROBE_TIMEOUT_MS: Long = 3_000L
    }
}

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
    /** True when a dead owner was released and a successor may now acquire [slot]. */
    suspend fun reconcile(slot: TurnLeaseSlot): Boolean {
        val owner = slot.lease ?: return false
        if (owner.phase == TurnLeasePhase.Retiring || owner.phase == TurnLeasePhase.Terminal) return false
        if (owner.isLocallyAliveWithoutRun) return false
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
        slot.updateOwner { telemetry ->
            if (telemetry == null) return@updateOwner null
            val same = telemetry.runtimeId == owner.runtimeId &&
                telemetry.conversationId == owner.conversationId &&
                telemetry.acquiredAtMs == owner.acquiredAtMs
            if (same) null else telemetry
        }
    }

    /** Moves the live lease with [token] to Retiring; null when no such live lease remains. */
    private fun retireLease(slot: TurnLeaseSlot, token: Long): TurnLease? {
        while (true) {
            val current = slot.lease ?: return null
            if (current.token != token) return null
            if (current.phase == TurnLeasePhase.Retiring || current.phase == TurnLeasePhase.Terminal) return null
            if (slot.casLease(current, current.copy(phase = TurnLeasePhase.Retiring))) return current
        }
    }
}
