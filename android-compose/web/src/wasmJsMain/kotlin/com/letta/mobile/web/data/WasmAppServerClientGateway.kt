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
import com.letta.mobile.data.session.graph.HostSessionBinding
import com.letta.mobile.data.session.graph.HostSessionGraphFactory
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
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.ui.chat.session.TimelineChipSources
import com.letta.mobile.ui.chat.session.TimelineModelSource
import com.letta.mobile.web.chat.WebChatBinding
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

class WasmAppServerClientGateway(
    private val scope: CoroutineScope,
) {
    private val httpClient = HttpClient(Js) { install(WebSockets) }
    private val mutableState = MutableStateFlow<BackendConnectionStatus>(BackendConnectionStatus.Unconfigured)
    private var activeSession: WasmAppServerSession? = null
    private val mutableGraph = MutableStateFlow<SharedSessionRepositoryGraph?>(null)
    private var nextBinding: HostSessionBinding? = null
    private val graphFactory = HostSessionGraphFactory { checkNotNull(nextBinding) { "No App Server session to bind" } }
    private var connectionMonitor: Job? = null
    private val requestSessionId = Random.nextLong().toString()
    private var requestSequence = 0

    val state: StateFlow<BackendConnectionStatus> = mutableState.asStateFlow()

    /**
     * letta-mobile-ckmub.17.1: the live session's repository graph, built by the shared factory desktop
     * uses; null while there is no connection.
     */
    val sessionGraph: StateFlow<SharedSessionRepositoryGraph?> = mutableGraph.asStateFlow()

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
            val session = connectWasmAppServerSession(config, scope, httpClient, ::nextRequestId)
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

            val graph = openGraph(config, session)
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
        val transport = AppServerTimelineTransport(
            AppServerTimelineConnection(
                turnEngine = session.engine,
                events = session.client.events,
                isConnected = session.isConnected,
                admin = { method, params -> session.admin(method.value, params, ::nextRequestId) },
                agentIdFor = { agent.id },
                backendId = BackendId(WEB_BACKEND_ID),
            ),
        )
        val target = TimelineChatTarget(agentId = agent.id.value, agentName = agent.name, conversationId = conversationId)
        val admin = session.appServerAdmin
        return WebChatBinding(
            target = target,
            transport = transport,
            controls = webRunControls(AppServerRunControls(session.engine), target),
            chips = TimelineChipSources(
                models = TimelineModelSource(
                    backend = ConversationModelBackend.of(admin),
                    conversation = { admin.getConversation(it.value) },
                    agentModel = agent.model,
                ),
                permissionModes = session.permissionModes,
            ),
        )
    }

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
    private fun openGraph(config: LettaConfig, session: WasmAppServerSession): SharedSessionRepositoryGraph {
        closeGraph()
        nextBinding = webSessionBinding(config, session.client, scope)
        return graphFactory.create().also { mutableGraph.value = it }
    }

    private fun closeGraph() {
        mutableGraph.value?.close()
        mutableGraph.value = null
        nextBinding = null
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

/** The page's stop and approval answers, scoped to [target]'s conversation. */
private fun webRunControls(controls: AppServerRunControls, target: TimelineChatTarget) = TimelineChatRunControls(
    stopRun = { controls.stop(AgentId(target.agentId), ConversationId(target.conversationId)) },
    answerApproval = { answer ->
        controls.answer(
            AppServerApprovalAnswer(
                agentId = target.agentId,
                conversationId = target.conversationId,
                requestId = answer.requestId,
                toolCallId = answer.toolCallIds.firstOrNull(),
                approve = answer.approve,
                reason = answer.reason,
            ),
        )
    },
)

internal fun webRequestId(sessionId: String, prefix: String, sequence: Int): String =
    "web-$sessionId-$prefix-$sequence"
