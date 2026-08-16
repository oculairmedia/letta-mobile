package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
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
    private var activeSession: WasmAppServerSession? = null
    private var connectionMonitor: Job? = null
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
            close()
            val session = connectWasmAppServerSession(config, scope, httpClient, ::nextRequestId)
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
        session?.close()
    }

    fun conversation(agentId: String): Flow<WebConversationUpdate> = flow {
        val session = activeSession ?: error("Connect to an App Server first")
        val conversationId = session.ensureConversation(agentId, ::nextRequestId)
        val (subscriberId, events) = session.router.subscribe(AgentId(agentId), ConversationId(conversationId))
        try {
            val response = session.admin(
                method = "message.list",
                params = buildJsonObject {
                    put("conversation_id", conversationId)
                    put("limit", "100")
                    put("order", "asc")
                },
                nextRequestId = ::nextRequestId,
            )
            val messages = response as? JsonArray ?: JsonArray(emptyList())
            val entries = AppServerProtocol.json
                .decodeFromJsonElement(ListSerializer(LettaMessage.serializer()), messages)
                .mapNotNull(LettaMessage::toWebEntry)
            emit(WebConversationUpdate.Snapshot(entries))
            events.collect { received ->
                decodeWebConversationUpdate(received)?.let { emit(it) }
            }
        } finally {
            session.router.unsubscribe(subscriberId)
        }
    }

    fun sendMessage(
        agentId: String,
        text: String,
        images: List<MessageContentPart.Image> = emptyList(),
    ): Flow<String> = flow {
        val session = activeSession ?: error("Connect to an App Server first")
        val conversationId = session.ensureConversation(agentId, ::nextRequestId)
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
                    contentPartsJson = images.takeIf { it.isNotEmpty() }
                        ?.let { buildContentParts(text, it).toJsonArray().toString() },
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

    private fun monitor(session: WasmAppServerSession) {
        connectionMonitor?.cancel()
        connectionMonitor = scope.launch {
            session.transport.isConnected.first { connected -> !connected }
            if (activeSession === session) {
                activeSession = null
                session.onTransportDisconnected()
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

    private companion object {
        const val AGENT_LIMIT = 100
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
