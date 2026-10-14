package com.letta.mobile.web.data

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.data.transport.appserver.AppServerApprovalAnswer
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRunControls
import com.letta.mobile.data.transport.appserver.AppServerTimelineConnection
import com.letta.mobile.data.transport.appserver.AppServerTimelineTransport
import com.letta.mobile.ui.chat.session.TimelineChatRunControls
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.web.chat.WebChatBinding
import com.letta.mobile.web.iroh.IrohWasmAppServerTransport
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WasmAppServerClientGateway(
    private val scope: CoroutineScope,
) {
    private val httpClient = HttpClient(Js) { install(WebSockets) }
    private val mutableState = MutableStateFlow<BackendConnectionStatus>(BackendConnectionStatus.Unconfigured)
    private var activeSession: WasmAppServerSession? = null
    private var connectionMonitor: Job? = null
    private val requestSessionId = Random.nextLong().toString()
    private var requestSequence = 0

    val state: StateFlow<BackendConnectionStatus> = mutableState.asStateFlow()

    suspend fun listAgents(config: LettaConfig): List<AgentItemState> {
        if (config.serverUrl.isBlank()) {
            close()
            mutableState.value = BackendConnectionStatus.Unconfigured
            return emptyList()
        }
        // letta-mobile-by4vm.13: the web row of the session route matrix decides, not the URL shape.
        if (!webSessionCapabilities(config).supports(SessionRoute.AGENTS)) {
            close()
            mutableState.value = BackendConnectionStatus.Failed(NO_BROWSER_ROUTE)
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

            val response = bounded(REQUEST_TIMEOUT_MS, "Agent list timed out") {
                session.client.adminRpc(
                    AppServerCommand.AdminRpc(
                        requestId = nextRequestId("agent-list"),
                        method = "agent.list",
                        params = buildJsonObject {
                            put("limit", AGENT_LIMIT.toString())
                            put("offset", "0")
                        },
                    ),
                )
            }
            check(response.success) { response.error ?: "Agent list failed" }
            mutableState.value = BackendConnectionStatus.Connected(session.label)
            monitor(session)
            decodeWebAgents(response.result as? JsonArray ?: JsonArray(emptyList()))
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
        connectionMonitor?.cancel()
        connectionMonitor = null
        session?.close()
    }

    /**
     * letta-mobile-o4ygk.4.5: what the shared chat page needs for [agent]'s conversation on the
     * live session: the conversation (its most recent one, or a new one), a timeline transport over
     * the session's turn engine and `admin_rpc`, and the run controls (stop, approvals).
     */
    suspend fun openChat(agent: AgentItemState): WebChatBinding {
        val session = activeSession ?: error("Connect to an App Server first")
        val conversationId = session.ensureConversation(agent.id, ::nextRequestId)
        val transport = AppServerTimelineTransport(
            AppServerTimelineConnection(
                turnEngine = session.engine,
                events = session.client.events,
                isConnected = session.transport.isConnected,
                admin = { method, params -> session.admin(method.value, params, ::nextRequestId) },
                agentIdFor = { AgentId(agent.id) },
                backendId = BackendId(WEB_BACKEND_ID),
            ),
        )
        val target = TimelineChatTarget(agentId = agent.id, agentName = agent.name, conversationId = conversationId)
        return WebChatBinding(
            target = target,
            transport = transport,
            controls = webRunControls(AppServerRunControls(session.engine), target),
        )
    }

    private fun monitor(session: WasmAppServerSession) {
        connectionMonitor?.cancel()
        connectionMonitor = scope.launch {
            session.transport.isConnected.first { connected -> !connected }
            if (activeSession === session) {
                activeSession = null
                session.onTransportDisconnected()
                val reason = (session.transport as? IrohWasmAppServerTransport)
                    ?.failureReason
                    ?.value
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::cleanBrowserErrorMessage)
                    ?: "Connection closed"
                mutableState.value = BackendConnectionStatus.Failed(reason)
            }
        }
    }

    private fun nextRequestId(prefix: String): String {
        requestSequence += 1
        return webRequestId(requestSessionId, prefix, requestSequence)
    }

    private companion object {
        const val AGENT_LIMIT = 100
        const val WEB_BACKEND_ID = "web-app-server"
    }
}

/**
 * letta-mobile-by4vm.13: what a browser session serves — the web row of the
 * [com.letta.mobile.data.backend.SessionRouteMatrix] (the chat page's agents and conversation over an
 * App Server reached by ws:// or Iroh). A blank address is no backend.
 */
internal fun webSessionCapabilities(config: LettaConfig): SessionCapabilities =
    SessionCapabilities.forSession(
        platform = SessionPlatform.WEB,
        kind = config.backendKind().takeUnless { config.serverUrl.isBlank() },
    )

private const val NO_BROWSER_ROUTE = "This backend has no route from a browser (a browser cannot run the on-device runtime)."

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
