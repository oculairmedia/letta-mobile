package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.chat.runtime.ArchivedConversations
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.node.embedded.embeddedNodeConnectConfig
import com.letta.mobile.data.node.embedded.embeddedNodeTransport
import com.letta.mobile.desktop.chat.DesktopAppServerRuntimeConfig
import com.letta.mobile.desktop.chat.desktopCanvasToolRegistry
import com.letta.mobile.desktop.runtime.DesktopBundledRuntimeNode
import com.letta.mobile.data.session.pins.pinSource
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.repository.api.rosterForLists
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.data.transport.iroh.NotebookPeerProvisioning
import com.letta.mobile.desktop.canvas.DesktopNotebookCanvasStore
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.desktop.chat.defaultDesktopAppServerGatewayFactory
import com.letta.mobile.desktop.data.DesktopCanonicalSendInstall
import com.letta.mobile.desktop.data.DesktopCanonicalTimelineHost
import com.letta.mobile.desktop.data.DesktopDataBindings
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

internal const val DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS = 30_000L
private const val DESKTOP_DEVICE_ID = "letta-desktop"

/** The connect config for [config]'s node: its `iroh://` address, or the desktop's own node (no token). */
private fun desktopIrohConnectConfig(config: LettaConfig): IrohConnectConfig =
    if (shouldAttachLocalNode(config)) {
        embeddedNodeConnectConfig(DESKTOP_DEVICE_ID)
    } else {
        IrohConnectConfig(
            baseShimUrl = config.serverUrl,
            token = config.accessToken.orEmpty(),
            deviceId = DESKTOP_DEVICE_ID,
            clientVersion = "letta-desktop-iroh",
        )
    }

/** The bundled runtime is served by the desktop's own node unless a developer App Server override is set. */
internal fun shouldAttachLocalNode(config: LettaConfig): Boolean =
    config.mode == LettaConfig.Mode.LOCAL && DesktopAppServerRuntimeConfig.fromProcess().serverUrl == null

private fun localNodeTransport(sessionScope: CoroutineScope, canvasSessions: CanvasSessionRegistry): IrohChannelTransport {
    DesktopBundledRuntimeNode.tools = { desktopCanvasToolRegistry(isIroh = false, canvasSessions) }
    return embeddedNodeTransport(DesktopBundledRuntimeNode.host, DESKTOP_DEVICE_ID, sessionScope)
}

/** iroh:// backend: one QUIC channel transport shared by the chat gateway and admin_rpc reads; closed on dispose. */
private fun createIrohTransport(config: LettaConfig): IrohChannelTransport =
    IrohChannelTransport(
        activeConfigProvider = { desktopIrohConnectConfig(config) },
        // d6e8g.9: reuse the persisted, vault-encrypted desktop identity so this
        // machine keeps one stable NodeId across reconnects (enables pairing).
        secretKeyStore = { com.letta.mobile.desktop.security.DesktopIrohIdentity.loadOrCreate() },
        notebookStore = DesktopNotebookCanvasStore.notebooks,
        notebookPeers = {
            NotebookPeerProvisioning.read(Path.of(System.getProperty("user.home"), ".letta-mobile", "notebooks", "peers.json"))
        },
    ).also { transport ->
        // Canvases ride beside the App Server on the same host, so every app on it shares them.
        com.letta.mobile.desktop.canvas.DesktopCanvasHostSync.client.attach(transport.readyHandle)
    }

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

private suspend fun connectIrohTransport(transport: IrohChannelTransport, config: LettaConfig) {
    connectWithParams(transport::connect, connectParamsFromIroh(config))
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

/** Mode-authoritative Iroh transport gate: `Mode.LOCAL` wins over a leftover `iroh://` serverUrl. */
internal fun shouldBindIrohTransport(config: LettaConfig): Boolean =
    config.mode != LettaConfig.Mode.LOCAL && IrohChannelTransport.isIrohUrl(config.serverUrl)

/** The window's node transport for [activeConfig]: an Iroh node, the desktop's own node, or null for a direct ws:// host. */
@Composable
internal fun rememberIrohTransport(
    activeConfig: LettaConfig,
    chatScope: CoroutineScope,
    canvasSessions: CanvasSessionRegistry,
): IrohChannelTransport? {
    val irohTransport = remember(activeConfig) {
        if (shouldBindIrohTransport(activeConfig)) {
            createIrohTransport(activeConfig)
        } else {
            // No Iroh host behind this backend: canvases stay on this device, and say so.
            com.letta.mobile.desktop.canvas.DesktopCanvasHostSync.client.detach()
            if (shouldAttachLocalNode(activeConfig)) localNodeTransport(chatScope, canvasSessions) else null
        }
    }
    DesktopTransportLifecycleEffect(
        DesktopTransportLifecycleRequest(
            transport = irohTransport,
            activeConfig = activeConfig,
            chatScope = chatScope,
            hooks = DesktopTransportLifecycleHooks(
                onConnect = ::connectIrohTransport,
                // by4vm.10: close, not disconnect: this instance dies with its config key, so its
                // owned coroutine job must be cancelled or every config change leaks it.
                onDisposeTransport = { t -> t.close() },
            ),
        ),
    )
    return irohTransport
}

/** Publishes the live channel transport into the session graph slot; a backend with none publishes null. */
@Composable
internal fun rememberAndPublishGraphChannelTransport(
    irohTransport: IrohChannelTransport?,
    publish: (com.letta.mobile.data.transport.api.IChannelTransport?) -> Unit,
): com.letta.mobile.data.transport.api.IChannelTransport? {
    androidx.compose.runtime.LaunchedEffect(irohTransport) {
        publish(irohTransport)
    }
    return irohTransport
}

internal data class DesktopChatRuntime(
    val bootstrapState: DesktopBootstrapState,
    val chatScope: CoroutineScope,
    val dataBindings: DesktopDataBindings,
    val agentDefaults: com.letta.mobile.data.runtime.AgentDefaultsRepository? = null,
    /** The window's live boards, which a direct App Server's canvas_* tools edit in place. */
    val canvasSessions: com.letta.mobile.data.canvas.CanvasSessionRegistry = com.letta.mobile.data.canvas.CanvasSessionRegistry(),
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
    // The canonical (paginated) route is installed per controller so it retires with the transport
    // generation that opened it. Its shared send coordinator sends over the Iroh channel transport,
    // the same instance the chat gateway uses, so only Iroh mode can serve it; App Server backends
    // keep the controller's in-memory timeline loop.
    val canonicalHost = remember(controller) {
        DesktopCanonicalTimelineHost(feedTransport = bindings.irohTransport).also { host ->
            val irohTransport = bindings.irohTransport ?: return@also
            host.installOn(
                controller,
                DesktopCanonicalSendInstall(
                    frameSource = irohTransport,
                    conversationRepository = { runtime.dataBindings.sessionGraphProvider.current.conversationRepository },
                    scope = runtime.chatScope,
                    activeConfig = { runtime.bootstrapState.config },
                    clientVersion = { DESKTOP_CANONICAL_CLIENT_VERSION },
                ),
            )
        }
    }
    // Closing the superseded controller is what cancels its send/select/
    // timeline/presence jobs and closes its gateway + timeline loop. Without
    // this, a transport or backend switch leaves the old controller streaming
    // alongside the new one for the life of the app.
    DisposableEffect(controller) {
        onDispose {
            controller.close()
            // Retires the conversation writers this controller's route opened. Detaching a view
            // never does that, so without it the ledger keeps a writer per conversation alive
            // across a backend switch.
            runtime.chatScope.launch { canonicalHost.close() }
        }
    }
    return controller
}

private fun buildDesktopChatController(
    bindings: DesktopChatControllerBindings,
): DesktopChatController {
    val runtime = bindings.runtime
    val agentRepository = {
        runtime.dataBindings.sessionGraphProvider.current.ifSupported(SessionRoute.AGENTS) { agentRepository }
    }
    val archived = ArchivedConversations(bindings.secureSettingsStore)
    return DesktopChatController(
        bootstrapState = runtime.bootstrapState,
        scope = runtime.chatScope,
        gatewayFactory = {
            bindings.irohTransport?.let { transport ->
                IrohAdminRpcChatGateway(
                    transport,
                    deviceLabel = DESKTOP_DEVICE_ID,
                    approvalControls = com.letta.mobile.data.runtime.IrohApprovalControls(
                        scope = runtime.chatScope,
                        transport = transport,
                        registry = runtime.dataBindings.permissionModeSettings.modes,
                        outboxStore = com.letta.mobile.data.chat.approval.SettingsApprovalOutboxStore(bindings.secureSettingsStore),
                    ),
                )
            }
                ?: createDefaultDesktopChatGateway(
                    runtime.bootstrapState.config,
                    appServerGatewayFactory = defaultDesktopAppServerGatewayFactory(
                        runtime.dataBindings.permissionModeSettings,
                        runtime.agentDefaults,
                        runtime.canvasSessions,
                    ),
                )
        },
        rosterProvider = { agentRepository()?.rosterForLists().orEmpty() },
        agentByIdProvider = { agentIds ->
            agentRepository()?.let { resolveDesktopAgents(agentIds, it) }.orEmpty()
        },
        loadArchivedConversationIds = { archived.load().mapTo(mutableSetOf()) { it.value } },
        persistArchivedConversationIds = { ids -> archived.save(ids.mapTo(mutableSetOf(), ::ConversationId)) },
        conversationChanges = bindings.irohTransport,
        conversationPrefs = com.letta.mobile.desktop.chat.DesktopConversationPrefs.from(
            bindings.secureSettingsStore, runtime.dataBindings.sessionGraphProvider.pinSource(),
        ),
    ).also { controller ->
        controller.bindActiveTurns(runtime.dataBindings.sessionGraphProvider.activeTurnSessions)
    }
}

internal class DesktopSubagentRegistry(
    val repository: SubagentRepository?,
    val activeSubagents: State<List<SubagentEntry>>,
    val subagentsWithHistory: State<List<SubagentEntry>>,
)

/** Inputs for the active-subagent registry. */
internal data class SubagentRegistryRequest(
    val parentScope: SubagentParentScope?,
    val irohTransport: IrohChannelTransport? = null,
    /** Phase 4c: prefer the session-graph repository when it is a real impl. */
    val graphSubagentRepository: SubagentRepository? = null,
)

@Composable
internal fun rememberSubagentRegistry(request: SubagentRegistryRequest): DesktopSubagentRegistry {
    val parentScope = request.parentScope
    val irohTransport = request.irohTransport
    val graphSubagentRepository = request.graphSubagentRepository
    // Graph owns Cron/SelfTodo/Subagent after Phase 4c. Own an Iroh-backed
    // repository only when the graph still exposes a non-concrete stub
    // (early bootstrap / tests without adapters).
    val ownedRepository = remember(irohTransport, graphSubagentRepository) {
        if (graphSubagentRepository != null) {
            null
        } else {
            irohTransport?.let { SubagentRepository(it, includeAll = true) }
        }
    }
    val subagentRepository = graphSubagentRepository ?: ownedRepository
    DisposableEffect(ownedRepository) {
        onDispose { ownedRepository?.close() }
    }
    val activeSubagents = produceState(initialValue = emptyList(), subagentRepository, parentScope) {
        collectScopedSubagents(parentScope?.let { subagentRepository?.activeSubagentsFlow(it) }) { value = it }
    }
    val subagentsWithHistory = produceState(initialValue = emptyList(), subagentRepository, parentScope) {
        collectScopedSubagents(parentScope?.let { subagentRepository?.subagentsWithHistoryFlow(it) }) { value = it }
    }
    return DesktopSubagentRegistry(subagentRepository, activeSubagents, subagentsWithHistory)
}

/** Resolve parent scope only when both conversation coordinates are present. */
internal fun subagentParentScope(parentAgentId: String?, parentConversationId: String?): SubagentParentScope? =
    if (parentAgentId != null && parentConversationId != null) SubagentParentScope(parentAgentId, parentConversationId) else null

private suspend fun collectScopedSubagents(
    entries: kotlinx.coroutines.flow.Flow<List<SubagentEntry>>?,
    emit: (List<SubagentEntry>) -> Unit,
) {
    if (entries == null) {
        emit(emptyList())
        return
    }
    entries.collect { emit(it) }
}

/** Sent in the handshake so a shim log can tell a desktop canonical client from the Android one. */
private const val DESKTOP_CANONICAL_CLIENT_VERSION = "letta-desktop-canonical"
