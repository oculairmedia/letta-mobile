package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopWsChannelTransport
import kotlinx.coroutines.CoroutineScope
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
    IrohChannelTransport(activeConfigProvider = { desktopIrohConnectConfig(config) })

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
    val onDisposeTransport: (T) -> Unit,
)

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
        if (active != null) {
            request.chatScope.launch { runCatching { request.hooks.onConnect(active, request.activeConfig) } }
        }
        onDispose { active?.let(request.hooks.onDisposeTransport) }
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
                onDisposeTransport = { t -> chatScope.launch { runCatching { t.disconnect() } } },
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
    val secureSettingsStore: DesktopFileSecureSettingsStore,
)

/** [DesktopChatController] wired for either backend (iroh admin_rpc or HTTP). */
@Composable
internal fun rememberDesktopChatController(
    bindings: DesktopChatControllerBindings,
): DesktopChatController {
    val runtime = bindings.runtime
    return remember(
        runtime.bootstrapState,
        runtime.chatScope,
        runtime.dataBindings.sessionGraphProvider,
        bindings.irohTransport,
    ) {
        buildDesktopChatController(bindings)
    }
}

private fun buildDesktopChatController(
    bindings: DesktopChatControllerBindings,
): DesktopChatController {
    val runtime = bindings.runtime
    val httpRepo = {
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
            resolveDesktopAgentNames(agentIds, bindings.irohAgentDirectory, httpRepo)
        },
        agentModelByIdProvider = { agentIds ->
            resolveDesktopAgentModels(agentIds, bindings.irohAgentDirectory, httpRepo)
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
 * Active-subagent registry (Background tasks). Desktop streams chat over SSE,
 * but the subagent registry only exists on the shim's mobile WS protocol, so
 * we open a lean WS side-channel and feed the shared SubagentRepository.
 * Skipped in iroh mode: IrohChannelTransport stubs sendSubagentList until
 * the iroh node serves the subagent registry.
 */
private fun createSubagentTransport(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    chatScope: CoroutineScope,
): DesktopWsChannelTransport? =
    activeConfig.takeIf { it.serverUrl.isNotBlank() && !it.accessToken.isNullOrBlank() && !irohMode }
        ?.let { DesktopWsChannelTransport(chatScope) }

@Composable
internal fun rememberSubagentRegistry(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    chatScope: CoroutineScope,
): DesktopSubagentRegistry {
    val subagentTransport = remember(activeConfig, irohMode) {
        createSubagentTransport(activeConfig, irohMode, chatScope)
    }
    val subagentRepository = remember(subagentTransport) {
        subagentTransport?.let { SubagentRepository(it, includeAll = true) }
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
    val activeSubagents = produceState(emptyList<SubagentEntry>(), subagentRepository) {
        subagentRepository?.activeSubagentsFlow()?.collect { value = it } ?: run { value = emptyList() }
    }
    return DesktopSubagentRegistry(subagentRepository, activeSubagents)
}
