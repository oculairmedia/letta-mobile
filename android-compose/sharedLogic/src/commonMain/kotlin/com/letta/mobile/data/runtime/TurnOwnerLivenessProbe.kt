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
