package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopWsChannelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

internal const val DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS = 30_000L
internal const val ARCHIVED_CONVERSATION_IDS_KEY = "conversations.archived_ids"
private const val DESKTOP_DEVICE_ID = "letta-desktop"

private fun desktopIrohConnectConfig(config: LettaConfig): IrohConnectConfig =
    IrohConnectConfig(
        baseShimUrl = config.serverUrl,
        token = config.accessToken.orEmpty(),
        deviceId = DESKTOP_DEVICE_ID,
        clientVersion = "letta-desktop-iroh",
    )

/**
 * iroh:// backend: one QUIC channel transport shared by the chat gateway,
 * admin_rpc reads, and (once implemented server-side) registries. Selected
 * purely by URL scheme, same as Android's SessionGraphFactory; null for HTTP
 * backends. Connects on entering composition and disconnects on dispose.
 */
private fun createIrohTransport(config: LettaConfig): IrohChannelTransport =
    IrohChannelTransport(
        activeConfigProvider = { desktopIrohConnectConfig(config) },
        // d6e8g.9: reuse the persisted, vault-encrypted desktop identity so this
        // machine keeps one stable NodeId across reconnects (enables pairing).
        secretKeyStore = { com.letta.mobile.desktop.security.DesktopIrohIdentity.loadOrCreate() },
    )

internal data class DesktopConnectParams(
    val baseShimUrl: String,
    val token: String,
    val deviceId: String,
    val clientVersion: String,
)

internal suspend fun connectWithParams(
    connect: suspend (String, String, String, String) -> Unit,
    params: DesktopConnectParams,
) {
    connect(params.baseShimUrl, params.token, params.deviceId, params.clientVersion)
}

private fun connectParamsFromIroh(config: LettaConfig): DesktopConnectParams {
    val connectConfig = desktopIrohConnectConfig(config)
    return DesktopConnectParams(
        baseShimUrl = connectConfig.baseShimUrl,
        token = connectConfig.token,
        deviceId = connectConfig.deviceId,
        clientVersion = connectConfig.clientVersion,
    )
}

private fun connectParamsFromWs(config: LettaConfig): DesktopConnectParams =
    DesktopConnectParams(
        baseShimUrl = config.serverUrl,
        token = config.accessToken.orEmpty(),
        deviceId = DESKTOP_DEVICE_ID,
        clientVersion = DESKTOP_DEVICE_ID,
    )

private suspend fun connectIrohTransport(transport: IrohChannelTransport, config: LettaConfig) {
    connectWithParams(transport::connect, connectParamsFromIroh(config))
}

private suspend fun connectSubagentTransport(transport: DesktopWsChannelTransport, config: LettaConfig) {
    // WS side-channel keeps the shorter clientVersion label used historically
    // for mobile-shim subagent registry dials (distinct from iroh's label).
    connectWithParams(transport::connect, connectParamsFromWs(config))
}

private data class DesktopTransportLifecycleHooks<T>(
    val onConnect: suspend (T, LettaConfig) -> Unit,
    /** Suspending so teardown can be ordered after the connect job unwinds. */
    val onDisposeTransport: suspend (T) -> Unit,
)

/**
 * Runs [block], letting cancellation propagate. `runCatching` must not be used
 * around suspending work: it swallows [CancellationException] and breaks
 * structured cancellation.
 */
private suspend fun runIgnoringFailures(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // Connect/disconnect failures surface through transport state.
    }
}

private data class DesktopTransportLifecycleRequest<T>(
    val transport: T?,
    val activeConfig: LettaConfig,
    val chatScope: CoroutineScope,
    val hooks: DesktopTransportLifecycleHooks<T>,
)

@Composable
private fun <T> DesktopTransportLifecycleEffect(request: DesktopTransportLifecycleRequest<T>) {
    DisposableEffect(request.transport, request.activeConfig) {
        val active = request.transport
        // Own the connect job so disposal can cancel an in-flight connect and
        // wait for it to unwind. Otherwise a rapid key change lets connect
        // complete AFTER disconnect, leaving a live transport nobody closes.
        val connectJob = active?.let { transport ->
            request.chatScope.launch {
                runIgnoringFailures { request.hooks.onConnect(transport, request.activeConfig) }
            }
        }
        onDispose {
            if (active == null) return@onDispose
            request.chatScope.launch {
                connectJob?.cancelAndJoin()
                runIgnoringFailures { request.hooks.onDisposeTransport(active) }
            }
        }
    }
}

@Composable
internal fun rememberIrohTransport(
    activeConfig: LettaConfig,
    chatScope: CoroutineScope,
): IrohChannelTransport? {
    val irohTransport = remember(activeConfig) {
        activeConfig.takeIf { IrohChannelTransport.isIrohUrl(it.serverUrl) }?.let(::createIrohTransport)
    }
    DesktopTransportLifecycleEffect(
        DesktopTransportLifecycleRequest(
            transport = irohTransport,
            activeConfig = activeConfig,
            chatScope = chatScope,
            hooks = DesktopTransportLifecycleHooks(
                onConnect = ::connectIrohTransport,
                onDisposeTransport = { t -> t.disconnect() },
            ),
        ),
    )
    return irohTransport
}

internal data class DesktopChatRuntime(
    val bootstrapState: DesktopBootstrapState,
    val chatScope: CoroutineScope,
    val dataBindings: DesktopDataBindings,
)

internal data class DesktopChatControllerBindings(
    val runtime: DesktopChatRuntime,
    val irohTransport: IrohChannelTransport?,
    val irohAgentDirectory: IrohAdminRpcAgentDirectory?,
    val secureSettingsStore: com.letta.mobile.data.storage.SecureSettingsStore,
)

/** [DesktopChatController] wired for either backend (iroh admin_rpc or HTTP). */
@Composable
internal fun rememberDesktopChatController(
    bindings: DesktopChatControllerBindings,
): DesktopChatController {
    val runtime = bindings.runtime
    val controller = remember(
        runtime.bootstrapState,
        runtime.chatScope,
        runtime.dataBindings.sessionGraphProvider,
        bindings.irohTransport,
    ) {
        buildDesktopChatController(bindings)
    }
    // Closing the superseded controller is what cancels its send/select/
    // timeline/presence jobs and closes its gateway + timeline loop. Without
    // this, a transport or backend switch leaves the old controller streaming
    // alongside the new one for the life of the app.
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}

private fun buildDesktopChatController(
    bindings: DesktopChatControllerBindings,
): DesktopChatController {
    val runtime = bindings.runtime
    val agentRepository = {
        runtime.dataBindings.sessionGraphProvider.current.agentRepository
    }
    return DesktopChatController(
        bootstrapState = runtime.bootstrapState,
        scope = runtime.chatScope,
        gatewayFactory = {
            bindings.irohTransport?.let { IrohAdminRpcChatGateway(it, deviceLabel = DESKTOP_DEVICE_ID) }
                ?: createDefaultDesktopChatGateway(runtime.bootstrapState.config)
        },
        agentNamesByIdProvider = { agentIds ->
            resolveDesktopAgentNames(agentIds, agentRepository())
        },
        agentByIdProvider = { agentIds ->
            resolveDesktopAgents(agentIds, agentRepository())
        },
        loadArchivedConversationIds = { loadArchivedConversationIds(bindings.secureSettingsStore) },
        persistArchivedConversationIds = { ids ->
            persistArchivedConversationIds(bindings.secureSettingsStore, ids)
        },
    )
}

/** The subagent side-channel plus the live active-subagent list it feeds. */
internal class DesktopSubagentRegistry(
    val repository: SubagentRepository?,
    val activeSubagents: State<List<SubagentEntry>>,
)

/**
 * Active-subagent registry (Background tasks) side-channel for HTTP backends:
 * desktop streams chat over SSE but the WS protocol carries the registry, so
 * a lean WS side-channel feeds the shared SubagentRepository. Skipped in iroh
 * mode — the registry rides the main iroh transport there (see
 * [rememberSubagentRegistry]).
 */
private fun createSubagentTransport(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    chatScope: CoroutineScope,
): DesktopWsChannelTransport? =
    activeConfig.takeIf { it.serverUrl.isNotBlank() && !it.accessToken.isNullOrBlank() && !irohMode }
        ?.let { DesktopWsChannelTransport(chatScope) }

/** Inputs for the active-subagent registry. */
internal data class SubagentRegistryRequest(
    val activeConfig: LettaConfig,
    val irohMode: Boolean,
    val parentScope: SubagentParentScope?,
    val irohTransport: IrohChannelTransport? = null,
)

@Composable
internal fun rememberSubagentRegistry(
    request: SubagentRegistryRequest,
    chatScope: CoroutineScope,
): DesktopSubagentRegistry {
    val activeConfig = request.activeConfig
    val irohMode = request.irohMode
    val parentScope = request.parentScope
    val irohTransport = request.irohTransport
    val subagentTransport = remember(activeConfig, irohMode) {
        createSubagentTransport(activeConfig, irohMode, chatScope)
    }
    // iroh:// now serves the registry natively (scoped subagent.list RPC), so
    // the repository rides the main iroh transport there; HTTP backends keep
    // the lean WS side-channel.
    val subagentRepository = remember(subagentTransport, irohTransport) {
        irohTransport?.let { SubagentRepository(it, includeAll = true) }
            ?: subagentTransport?.let { SubagentRepository(it, includeAll = true) }
    }
    // The repository launches push/reconnect collectors on construction; a
    // replaced instance must be closed or they outlive it forever.
    DisposableEffect(subagentRepository) {
        onDispose { subagentRepository?.close() }
    }
    DesktopTransportLifecycleEffect(
        DesktopTransportLifecycleRequest(
            transport = subagentTransport,
            activeConfig = activeConfig,
            chatScope = chatScope,
            hooks = DesktopTransportLifecycleHooks(
                onConnect = ::connectSubagentTransport,
                onDisposeTransport = { it.close() },
            ),
        ),
    )
    val activeSubagents = produceState(
        initialValue = emptyList(),
        subagentRepository,
        parentScope,
    ) {
        collectScopedActiveSubagents(subagentRepository, parentScope) { value = it }
    }
    return DesktopSubagentRegistry(subagentRepository, activeSubagents)
}

/** Resolve parent scope only when both conversation coordinates are present. */
internal fun subagentParentScope(
    parentAgentId: String?,
    parentConversationId: String?,
): SubagentParentScope? =
    parentAgentId?.let { agentId ->
        parentConversationId?.let { conversationId ->
            SubagentParentScope(agentId, conversationId)
        }
    }

private suspend fun collectScopedActiveSubagents(
    repository: SubagentRepository?,
    parentScope: SubagentParentScope?,
    emit: (List<SubagentEntry>) -> Unit,
) {
    val scopedRepository = repository ?: run {
        emit(emptyList())
        return
    }
    val scope = parentScope ?: run {
        emit(emptyList())
        return
    }
    scopedRepository.activeSubagentsFlow(scope).collect { emit(it) }
}
