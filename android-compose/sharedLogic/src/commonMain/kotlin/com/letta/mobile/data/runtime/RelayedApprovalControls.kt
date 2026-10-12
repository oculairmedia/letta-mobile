package com.letta.mobile.data.runtime

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * letta-mobile-bzvro.40: an Iroh client's side of the approval relay: the host's `pending_approvals`
 * snapshots folded into one [RelayedPendingApprovalStore].
 *
 * Nothing is cleared on a disconnect: the cards stay, so a decision can still be made (and queued)
 * while the link is down, and the host's replay to the reconnected viewer, an empty list included,
 * says which of them it no longer holds.
 */
class ApprovalRelayClient(private val store: RelayedPendingApprovalStore = RelayedPendingApprovalStore()) {
    /** Every request the host reported parked, by tool call id. */
    val pending: StateFlow<Map<String, PendingApprovalDetails>> get() = store.pending

    /** Folds the host's snapshots from [frames] (a channel transport's `events`) until [scope] ends. */
    fun bind(scope: CoroutineScope, frames: Flow<ServerFrame>): Job = scope.launch {
        frames.filterIsInstance<ServerFrame.PendingApprovalsUpdated>().collect { store.apply(it.decode()) }
    }

    /** Folds raw App Server frames' JSON (a desktop gateway's client events), keeping only `pending_approvals`. */
    fun bindRaw(scope: CoroutineScope, frames: Flow<JsonObject>): Job = scope.launch {
        frames.collect { raw -> ApprovalRelay.decode(raw)?.let(store::apply) }
    }

    /** The answer for [call] was accepted: its card goes now, before the host's next snapshot. */
    fun resolve(call: com.letta.mobile.runtime.ToolCallId) = store.resolve(call)
}

/**
 * letta-mobile-bzvro.40: the permission-mode chip for a conversation whose turns run on an Iroh host.
 *
 * [modes] holds what this client chose (persisted per conversation, the stricter-of rule while a
 * change is pending); the host's runtime is changed with `permission_mode.set` and read with
 * `permission_mode.get` ([PermissionModeRpc]). Offered only when the host advertises
 * [PermissionModeRpc.CAPABILITY] ([supported]); an older host keeps no chip.
 */
class RelayedPermissionModes(
    val modes: PermissionModeRegistry,
    private val rpc: AdminRpcInvoker,
    private val hostCapabilities: () -> Set<String>,
) {
    val supported: Boolean get() = PermissionModeRpc.CAPABILITY in hostCapabilities()

    fun observe(runtime: AppServerRuntimeScope): Flow<PermissionModeState> = modes.observe(runtime)

    /** Asks the host for [mode]; true once its App Server runs the runtime in it. */
    suspend fun set(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean =
        modes.change(runtime, mode) { requested -> request(runtime, requested) }

    /**
     * Brings the chip and the host into agreement when a conversation opens: a choice this client
     * persisted (or a default stricter than the host's) is sent to the host, since a restarted host
     * forgets it; otherwise the host's mode is adopted, which is how a change made by another client
     * shows here.
     */
    suspend fun sync(runtime: AppServerRuntimeScope) {
        if (!supported) return
        val local = modes.observe(runtime).first()
        val host = runCatching { PermissionModeRpc.modeOf(rpc.invoke(PermissionModeRpc.GET, params(runtime, null))) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        when {
            local.appliesOnStart -> set(runtime, local.mode)
            host == null && local.mode != RuntimePermissionDefaults.DEFAULT_MODE -> set(runtime, local.mode)
            host != null -> modes.observed(runtime, host)?.let { pending -> set(runtime, pending) }
        }
    }

    private suspend fun request(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): ModeChangeResult = try {
        val result = rpc.invoke(PermissionModeRpc.SET, params(runtime, mode))
        if (PermissionModeRpc.confirmedOf(result) && PermissionModeRpc.modeOf(result) == mode) {
            ModeChangeResult.Confirmed
        } else {
            ModeChangeResult.Unconfirmed
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Telemetry.event("RelayedPermissionModes", "set.failed", "class" to error::class.simpleName, level = Telemetry.Level.WARN)
        ModeChangeResult.Unconfirmed
    }

    private fun params(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode?): JsonObject =
        PermissionModeRpc.params(runtime.agentId, runtime.conversationId, mode)
}

/**
 * letta-mobile-bzvro.40: the approval relay and the permission-mode chip for a gateway that talks to
 * an Iroh host over [transport] (desktop over `iroh://`). Owns one child job of [scope], ended by
 * [close], that folds the host's `pending_approvals` snapshots.
 */
class IrohApprovalControls(
    scope: CoroutineScope,
    private val transport: IChannelTransport,
    registry: PermissionModeRegistry,
) : AutoCloseable {
    val relay: ApprovalRelayClient = ApprovalRelayClient()
    val modes: RelayedPermissionModes = RelayedPermissionModes(
        modes = registry,
        rpc = AdminRpcInvoker.overTransport { transport },
        hostCapabilities = { transport.hostCapabilities },
    )
    private val job: Job = relay.bind(scope, transport.events)

    override fun close() {
        job.cancel()
    }
}
