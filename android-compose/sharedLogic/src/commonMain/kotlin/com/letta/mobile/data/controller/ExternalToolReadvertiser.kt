package com.letta.mobile.data.controller

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolAdvertisementState
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Re-advertises the external tools to every active runtime when the registry's live sources
 * change (letta-mobile-s416w.27, decision R2).
 *
 * The protocol has no "update tools" frame, but letta-code (verified against the pinned 0.29.12)
 * replaces a live runtime's external tools on a repeated `runtime_start` for the same agent and
 * conversation, and snapshots the tool list when a turn starts. So a change is re-sent as one
 * `runtime_start` per active runtime and reaches the agent from its next turn. See
 * docs/architecture/live-external-tools.md for the evidence.
 *
 * The re-issued frame carries only the scope, the client info and the whole current tool set: no
 * `mode` or `cwd` (the server leaves both untouched when absent), and `recover_approvals` /
 * `force_device_status` off so the server does not replay approvals or device status to a client
 * that is already in sync. Changes are debounced so a burst (a plugin publishing several actions)
 * costs one round.
 */
internal class ExternalToolReadvertiser(
    private val client: AppServerClient,
    private val registry: ExternalToolRegistry,
    private val clientInfo: AppServerRuntimeStartClientInfo,
    private val requestIdFactory: () -> String,
    /** The runtimes started on the live connection; a reconnect re-starts them with the current set. */
    private val activeRuntimes: suspend () -> List<AppServerRuntimeScope>,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val _state = MutableStateFlow(ToolAdvertisementState())

    /** Pending from a change until every active runtime has been sent the new set. */
    val state: StateFlow<ToolAdvertisementState> = _state.asStateFlow()

    fun attach(scope: CoroutineScope): Job = scope.launch {
        registry.toolsChanged.collectLatest {
            _state.value = ToolAdvertisementState(pending = true)
            delay(debounceMs)
            activeRuntimes().forEach { runtime -> readvertise(runtime) }
            _state.value = ToolAdvertisementState(pending = false)
        }
    }

    private suspend fun readvertise(runtime: AppServerRuntimeScope) {
        val command = AppServerCommand.RuntimeStart(
            requestId = requestIdFactory(),
            agentId = runtime.agentId,
            conversationId = runtime.conversationId,
            clientInfo = clientInfo,
            recoverApprovals = false,
            forceDeviceStatus = false,
            externalTools = registry.advertisedToolsCommandGroups(),
        )
        val failure = sendForFailure(command) ?: return
        // The runtime keeps its previous tools; the next runtime_start (a reconnect, or the next
        // change) carries the current set again.
        Telemetry.event(
            TELEMETRY_SOURCE, "externalTools.readvertiseFailed",
            "agentId" to runtime.agentId,
            "conversationId" to runtime.conversationId,
            "error" to failure,
            level = Telemetry.Level.WARN,
        )
    }

    /** Sends [command]; null when the server accepted it, otherwise why it did not. */
    private suspend fun sendForFailure(command: AppServerCommand.RuntimeStart): String? = try {
        val response = client.runtimeStart(command)
        if (response.success) null else response.error ?: "runtime_start failed"
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        e.message ?: e::class.simpleName ?: "runtime_start failed"
    }

    companion object {
        const val DEBOUNCE_MS: Long = 500L
        private const val TELEMETRY_SOURCE = "DefaultAppServerController"
    }
}
