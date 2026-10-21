package com.letta.mobile.web.data

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.canvas.CanvasRelayDial
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.data.session.graph.HostSessionBinding
import com.letta.mobile.data.memory.memfs.AppServerMemfsSource
import com.letta.mobile.data.memory.memfs.MemfsSource
import com.letta.mobile.data.model.isIrohBackendUrl
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.chat.approval.SettingsApprovalOutboxStore
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.runtime.IrohApprovalControls
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.session.graph.HostSessionGraphFactory
import com.letta.mobile.data.session.graph.HostSessionGraphProvider
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.session.graph.hostServedKind
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.data.transport.appserver.AppServerApprovalAnswer
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRunControls
import com.letta.mobile.data.transport.appserver.AppServerTimelineConnection
import com.letta.mobile.data.transport.appserver.AppServerTimelineTransport
import com.letta.mobile.ui.chat.session.ConversationModelBackend
import com.letta.mobile.ui.chat.session.TimelineChatRunControls
import com.letta.mobile.ui.chat.session.SubagentTimelineRoute
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.ui.chat.session.TimelineChipSources
import com.letta.mobile.ui.chat.session.TimelineModelSource
import com.letta.mobile.web.chat.WebChatBinding
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

class WasmAppServerClientGateway(
    private val scope: CoroutineScope,
    /** The browser's settings store: the permission-mode choices and the approval outbox live there. */
    private val settings: SecureSettingsStore,
) {
    /**
     * letta-mobile-ckmub.17.25: the composer's mode choices and the Settings default mode, kept in the
     * browser's storage so a reload keeps them, over ws:// and Iroh alike.
     */
    val permissionModeSettings: PermissionModeSettings = PermissionModeSettings(settings)

    private val httpClient = HttpClient(Js) { install(WebSockets) }
    private val mutableState = MutableStateFlow<BackendConnectionStatus>(BackendConnectionStatus.Unconfigured)
    private val liveSession = MutableStateFlow<WasmAppServerSession?>(null)
    private var activeSession: WasmAppServerSession?
        get() = liveSession.value
        set(value) {
            liveSession.value = value
        }
    private var nextBinding: HostSessionBinding? = null
    private var connectionMonitor: Job? = null
    private val requestSessionId = Random.nextLong().toString()
    private var requestSequence = 0

    val state: StateFlow<BackendConnectionStatus> = mutableState.asStateFlow()

    /**
     * letta-mobile-ckmub.17.1 / ckmub.17.5: the live session's repository graph, built by the shared
     * factory and provider desktop uses. With no connection it is the graph of no backend (every route
     * unserved), which the shared pages bind the same way.
     */
    val sessionGraphProvider: HostSessionGraphProvider = HostSessionGraphProvider(
        HostSessionGraphFactory { nextBinding ?: disconnectedWebBinding() },
        SessionPlatform.WEB,
    )

    /** letta-mobile-ckmub.17.5: the agent's MemFS files over the live session, with its `memory_updated` pushes. */
    /** The provider and model catalog over the live session's `admin_rpc`, as desktop's Providers reads them. */
    val modelControl: ModelControlSession = ModelControlSession(
        AdminRpcInvoker.overTransport { activeSession?.let { sessionGraphProvider.current.channelTransport } },
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val memfs: MemfsSource = AppServerMemfsSource(
        client = { activeSession?.client ?: error("Connect to an App Server first") },
        events = liveSession.flatMapLatest { session -> session?.client?.events ?: emptyFlow() },
        requestId = ::nextRequestId,
    )

    private val mutableCanvasRelay = MutableStateFlow<CanvasRelayDial?>(null)

    /**
     * letta-mobile-ckmub.17.9: the way to the live session's canvas relay, once it has authenticated
     * (the host admits the relay only for an authenticated peer); null while there is none. The
     * shared [com.letta.mobile.data.canvas.CanvasRelayAttachment] follows it.
     */
    val canvasRelay: StateFlow<CanvasRelayDial?> = mutableCanvasRelay.asStateFlow()

    suspend fun listAgents(config: LettaConfig): List<Agent> {
        unservedStatus(config)?.let { status ->
            close()
            mutableState.value = status
            return emptyList()
        }

        mutableState.value = BackendConnectionStatus.Connecting
        return try {
            close()
            val session = connectWasmAppServerSession(config, scope, httpClient, permissionModeSettings.modes, ::nextRequestId)
            activeSession = session
            val auth = bounded(REQUEST_TIMEOUT_MS, "App Server authentication timed out") {
                session.client.auth(
                    AppServerCommand.Auth(
                        requestId = nextRequestId("auth"),
                        token = config.accessToken.orEmpty(),
                        capabilities = authCapabilitiesFor(config.serverUrl),
                    ),
                )
            }
            check(auth.success) { auth.error ?: "App Server authentication failed" }

            val graph = openGraph(config, session, auth.capabilities.orEmpty().toSet())
            if (isIrohBackendUrl(config.serverUrl)) session.bindIrohChat(irohChatGateway())
            bounded(REQUEST_TIMEOUT_MS, "Agent list timed out") { graph.agentRepository.refreshAgents() }
            mutableState.value = BackendConnectionStatus.Connected(session.label)
            mutableCanvasRelay.value = session.canvasRelay
            monitor(session)
            graph.agentRepository.agents.value
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (error: Throwable) {
            close()
            mutableState.value = BackendConnectionStatus.Failed(error.displayMessage("Connection failed"))
            throw error
        }
    }

    suspend fun close() {
        val session = activeSession
        activeSession = null
        mutableCanvasRelay.value = null
        connectionMonitor?.cancel()
        connectionMonitor = null
        closeGraph()
        session?.close()
    }

    /**
     * letta-mobile-o4ygk.4.5: what the shared chat page needs for [agent]'s conversation on the
     * live session: the conversation (its most recent one, or a new one), a timeline transport over
     * the session's turn engine and `admin_rpc`, and the run controls (stop, approvals).
     */
    suspend fun openChat(agent: Agent): WebChatBinding {
        val session = activeSession ?: error("Connect to an App Server first")
        val conversationId = session.ensureConversation(agent.id.value, ::nextRequestId)
        val transport = timelineTransport(session, agent.id)
        val target = TimelineChatTarget(agentId = agent.id.value, agentName = agent.name, conversationId = conversationId)
        return WebChatBinding(
            target = target,
            transport = transport,
            controls = webRunControls(AppServerRunControls(session.engine), session.irohChat, target),
            hostApprovals = session.irohChat,
            chips = TimelineChipSources(
                models = webModelSource(session.irohChat, session.appServerAdmin, agent.model, modelControl.catalog),
                permissionModes = session.permissionModes,
            ),
        )
    }

    /**
     * The shared Iroh chat gateway over the live session's channel, as desktop binds it: its models
     * (`model.list` / `model.update`), and through the shared [IrohApprovalControls] the host's parked
     * approvals (`pending_approvals`, replayed when the chat hydrates), `approval.submit` answers
     * through the durable outbox, and the mode chip over `permission_mode.get/set`.
     */
    private fun irohChatGateway(): IrohAdminRpcChatGateway {
        val channel = sessionGraphProvider.current.channelTransport
        return IrohAdminRpcChatGateway(
            channel,
            deviceLabel = WEB_BACKEND_ID,
            approvalControls = IrohApprovalControls(
                scope = scope,
                transport = channel,
                registry = permissionModeSettings.modes,
                outboxStore = SettingsApprovalOutboxStore(settings),
            ),
        )
    }

    /**
     * letta-mobile-zxk2n.8: where a subagent's conversation is read for the chat's dual view, over the
     * live session's socket; null while no App Server is connected.
     */
    fun subagentTimeline(view: SubagentView): SubagentTimelineRoute? {
        val session = activeSession ?: return null
        return SubagentTimelineRoute(timelineTransport(session, view.agentId), view.conversationId)
    }

    /** A conversation's timeline over [session]'s socket, for [agent]'s conversations. */
    private fun timelineTransport(session: WasmAppServerSession, agent: AgentId): AppServerTimelineTransport =
        AppServerTimelineTransport(
            AppServerTimelineConnection(
                turnEngine = session.engine,
                events = session.client.events,
                isConnected = session.isConnected,
                admin = { method, params -> session.admin(method.value, params, ::nextRequestId) },
                agentIdFor = { agent },
                backendId = BackendId(WEB_BACKEND_ID),
            ),
        )

    private fun monitor(session: WasmAppServerSession) {
        connectionMonitor?.cancel()
        connectionMonitor = scope.launch {
            session.isConnected.first { connected -> !connected }
            if (activeSession === session) {
                activeSession = null
                mutableCanvasRelay.value = null
                closeGraph()
                session.onTransportDisconnected()
                val reason = session.failureReason()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::cleanBrowserErrorMessage)
                    ?: "Connection closed"
                mutableState.value = BackendConnectionStatus.Failed(reason)
            }
        }
    }

    /** The shared session graph over [session], replacing the previous connection's. */
    private fun openGraph(
        config: LettaConfig,
        session: WasmAppServerSession,
        hostCapabilities: Set<String>,
    ): SharedSessionRepositoryGraph {
        nextBinding = webSessionBinding(config, session.client, scope, hostCapabilities)
        return sessionGraphProvider.rebuild()
    }

    /** Back to the graph of no backend; the previous generation closes. */
    private fun closeGraph() {
        if (nextBinding == null) return
        nextBinding = null
        sessionGraphProvider.rebuild()
    }

    private fun nextRequestId(prefix: String): String {
        requestSequence += 1
        return webRequestId(requestSessionId, prefix, requestSequence)
    }

    private companion object {
        const val WEB_BACKEND_ID = "web-app-server"
    }
}

/**
 * letta-mobile-by4vm.13: what a browser session serves — the web row of the
 * [com.letta.mobile.data.backend.SessionRouteMatrix], the same as desktop's for a ws:// or Iroh host
 * (letta-mobile-ckmub.17.1). A blank address is no backend.
 */
internal fun webSessionCapabilities(config: LettaConfig): SessionCapabilities =
    SessionCapabilities.forSession(
        platform = SessionPlatform.WEB,
        kind = hostServedKind(config),
    )

private const val NO_BROWSER_ROUTE = "This backend has no route from a browser (a browser cannot run the on-device runtime)."

/**
 * Why [config] cannot list agents from a browser, or null when it can: no address, or (letta-mobile-by4vm.13)
 * a backend kind outside the web row of the session route matrix.
 */
internal fun unservedStatus(config: LettaConfig): BackendConnectionStatus? = when {
    config.serverUrl.isBlank() -> BackendConnectionStatus.Unconfigured
    !webSessionCapabilities(config).supports(SessionRoute.AGENTS) -> BackendConnectionStatus.Failed(NO_BROWSER_ROUTE)
    else -> null
}

/**
 * Where a chat lists and switches models. An Iroh host's control channel answers no native
 * `list_models` / `conversation_update`, so there the chat goes through the shared Iroh chat gateway
 * ([iroh]: `model.list`, and `model.update` with the effort [catalog] advertises), as desktop does;
 * a ws:// App Server keeps its own commands ([admin]).
 */
internal fun webModelSource(
    iroh: IrohAdminRpcChatGateway?,
    admin: AppServerLocalAdminGateway,
    agentModel: String?,
    catalog: ModelCatalogRepository,
): TimelineModelSource {
    val gateway = iroh ?: return TimelineModelSource(
        backend = ConversationModelBackend.of(admin),
        conversation = { admin.getConversation(it.value) },
        agentModel = agentModel,
    )
    return TimelineModelSource(
        backend = checkNotNull(ConversationModelBackend.of(gateway)),
        conversation = { gateway.getConversation(it.value) },
        agentModel = agentModel,
        catalog = catalog,
    )
}

/**
 * The page's stop and approval answers, scoped to [target]'s conversation. Over an Iroh host the
 * answer goes to the host that parked the request ([iroh]'s `approval.submit`); over ws:// to this
 * session's turn engine.
 */
private fun webRunControls(
    controls: AppServerRunControls,
    iroh: IrohAdminRpcChatGateway?,
    target: TimelineChatTarget,
) = TimelineChatRunControls(
    stopRun = { controls.stop(AgentId(target.agentId), ConversationId(target.conversationId)) },
    answerApproval = { answer ->
        val toolCallId = answer.toolCallIds.firstOrNull()
        if (iroh != null) {
            iroh.submitApproval(target.agentId, target.conversationId, answer.requestId, toolCallId, answer.approve, answer.reason)
        } else {
            controls.answer(
                AppServerApprovalAnswer(
                    agentId = target.agentId,
                    conversationId = target.conversationId,
                    requestId = answer.requestId,
                    toolCallId = toolCallId,
                    approve = answer.approve,
                    reason = answer.reason,
                ),
            )
        }
    },
)

internal fun webRequestId(sessionId: String, prefix: String, sequence: Int): String =
    "web-$sessionId-$prefix-$sequence"
