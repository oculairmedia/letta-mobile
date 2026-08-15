package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.web.iroh.IrohWasmAppServerTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput

class WasmAppServerClientGateway(
    private val scope: CoroutineScope,
) {
    private val httpClient = HttpClient(Js) { install(WebSockets) }
    private val mutableState = MutableStateFlow<WebConnectionState>(WebConnectionState.Unconfigured)
    private var activeSession: Session? = null
    private var connectionMonitor: Job? = null
    private val conversationByAgent = mutableMapOf<String, String>()
    private var requestSequence = 0

    val state: StateFlow<WebConnectionState> = mutableState.asStateFlow()

    suspend fun listAgents(config: LettaConfig): List<AgentItemState> {
        if (config.serverUrl.isBlank()) {
            close()
            mutableState.value = WebConnectionState.Unconfigured
            return emptyList()
        }

        mutableState.value = WebConnectionState.Connecting
        return try {
            val session = connect(config)
            activeSession = session
            val auth = bounded(REQUEST_TIMEOUT_MS, "App Server authentication timed out") {
                session.client.auth(
                    AppServerCommand.Auth(
                        requestId = nextRequestId("auth"),
                        token = config.accessToken.orEmpty(),
                        capabilities = null,
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
            mutableState.value = WebConnectionState.Connected(session.label)
            monitor(session)
            decodeWebAgents(response.result as? JsonArray ?: JsonArray(emptyList()))
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (error: Throwable) {
            close()
            mutableState.value = WebConnectionState.Failed(error.message ?: "Connection failed")
            throw error
        }
    }

    suspend fun close() {
        val session = activeSession
        activeSession = null
        connectionMonitor?.cancel()
        connectionMonitor = null
        conversationByAgent.clear()
        session?.close?.invoke()
    }

    suspend fun loadConversation(agentId: String): List<WebChatEntry> {
        val session = activeSession ?: error("Connect to an App Server first")
        val conversationId = ensureConversation(session, agentId)
        val response = admin(
            session = session,
            method = "message.list",
            params = buildJsonObject {
                put("conversation_id", conversationId)
                put("limit", "100")
                put("order", "asc")
            },
        )
        val messages = response as? JsonArray ?: JsonArray(emptyList())
        return AppServerProtocol.json
            .decodeFromJsonElement(ListSerializer(LettaMessage.serializer()), messages)
            .mapNotNull(LettaMessage::toWebEntry)
    }

    fun sendMessage(agentId: String, text: String): Flow<String> = flow {
        val session = activeSession ?: error("Connect to an App Server first")
        val conversationId = ensureConversation(session, agentId)
        var assistantText = ""
        session.engine.runTurn(
            TurnCommand(
                backendId = BackendId("web-app-server"),
                runtimeId = RuntimeId("web-app-server"),
                agentId = AgentId(agentId),
                conversationId = ConversationId(conversationId),
                input = TurnInput.UserMessage(
                    localMessageId = nextRequestId("message"),
                    text = text,
                ),
            ),
        ).collect { event ->
            when (val payload = event.payload) {
                is RuntimeEventPayload.RemoteStreamFrame -> {
                    val delta = decodeAssistantDelta(payload) ?: return@collect
                    assistantText = mergeAssistantText(assistantText, delta)
                    emit(assistantText)
                }
                is RuntimeEventPayload.RunLifecycleChanged -> {
                    if (payload.status == RuntimeRunStatus.Failed) {
                        error(payload.reason ?: "Agent turn failed")
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun connect(config: LettaConfig): Session {
        close()
        val transport: AppServerTransport
        val label: String
        val close: suspend () -> Unit
        if (config.serverUrl.startsWith("iroh://")) {
            val iroh = IrohWasmAppServerTransport.connect(config.serverUrl, scope)
            transport = iroh
            label = "Iroh"
            close = iroh::close
        } else {
            val websocket = KtorAppServerWebSocketTransport(
                httpClient = httpClient,
                baseUrl = resolveWebSocketUrl(config.serverUrl),
                scope = scope,
                bearerToken = config.accessToken,
            )
            transport = websocket
            label = "WebSocket"
            close = websocket::close
        }
        try {
            bounded(CONNECT_TIMEOUT_MS, "Connection timed out") { transport.isConnected.first { it } }
            val client = DefaultAppServerClient(transport, parentScope = scope)
            return Session(
                client = client,
                engine = AppServerTurnEngine(
                    client = client,
                    requestIdFactory = { nextRequestId("turn") },
                ),
                transport = transport,
                label = label,
                close = close,
            )
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private suspend fun ensureConversation(session: Session, agentId: String): String {
        conversationByAgent[agentId]?.let { return it }
        val listed = admin(
            session = session,
            method = "conversation.list",
            params = buildJsonObject {
                put("limit", "100")
                put("order", "desc")
                put("order_by", "last_message_at")
            },
        ) as? JsonArray ?: JsonArray(emptyList())
        val existing = AppServerProtocol.json
            .decodeFromJsonElement(ListSerializer(Conversation.serializer()), listed)
            .firstOrNull { it.agentId.value == agentId }
        val conversation = existing ?: AppServerProtocol.json.decodeFromJsonElement(
            Conversation.serializer(),
            admin(
                session = session,
                method = "conversation.create",
                params = buildJsonObject { put("agent_id", agentId) },
            ) ?: error("Conversation create returned no result"),
        )
        return conversation.id.value.also { conversationByAgent[agentId] = it }
    }

    private suspend fun admin(
        session: Session,
        method: String,
        params: JsonObject,
    ) = bounded(REQUEST_TIMEOUT_MS, "$method timed out") {
        val response = session.client.adminRpc(
            AppServerCommand.AdminRpc(
                requestId = nextRequestId(method.substringAfterLast('.')),
                method = method,
                params = params,
            ),
        )
        check(response.success) { response.error ?: "$method failed" }
        response.result
    }

    private fun monitor(session: Session) {
        connectionMonitor?.cancel()
        connectionMonitor = scope.launch {
            session.transport.isConnected.first { connected -> !connected }
            if (activeSession === session) {
                activeSession = null
                conversationByAgent.clear()
                mutableState.value = WebConnectionState.Failed("Connection closed")
            }
        }
    }

    private fun nextRequestId(prefix: String): String {
        requestSequence += 1
        return "web-$prefix-$requestSequence"
    }

    private suspend fun <T> bounded(
        timeoutMs: Long,
        message: String,
        block: suspend () -> T,
    ): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (timeout: TimeoutCancellationException) {
        throw IllegalStateException(message, timeout)
    }

    private data class Session(
        val client: DefaultAppServerClient,
        val engine: AppServerTurnEngine,
        val transport: AppServerTransport,
        val label: String,
        val close: suspend () -> Unit,
    )

    private companion object {
        const val AGENT_LIMIT = 100
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
