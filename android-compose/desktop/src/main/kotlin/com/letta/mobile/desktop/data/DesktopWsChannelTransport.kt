package com.letta.mobile.desktop.data

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportDefaults
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ClientFrame
import com.letta.mobile.data.transport.HelloFrame
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.data.transport.SubagentListFrame
import com.letta.mobile.data.transport.SubagentTodosFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.encodeJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.time.Instant
import java.util.UUID
import dev.nucleusframework.nativessl.NativeTrustManager

import kotlin.time.Duration.Companion.milliseconds
/**
 * Lean desktop implementation of [IChannelTransport] over the shim's mobile WS
 * protocol (`{serverUrl}/shim/v1/mobile`), built on Ktor's WebSocket client.
 *
 * Desktop streams chat over SSE (see DesktopLettaHttpChatGateway); this is a
 * *side-channel* opened purely for the registries that only exist on the shim's
 * WS protocol — currently the active-subagent registry that backs the Background
 * tasks panel (letta-mobile-0yf7o, phase 2). It deliberately implements only the
 * subagent subset; the chat/cron/a2ui methods are not used on desktop.
 *
 * Protocol (verified against the live shim): on connect, open the socket and
 * send a [HelloFrame]; the shim replies with [ServerFrame.Welcome] →
 * [ChannelTransportState.Connected]. Requests carry a `request_id`; the matching
 * response frame is correlated back to the caller. `subagents_updated` pushes
 * arrive unsolicited on [events].
 */
class DesktopWsChannelTransport(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = defaultWsClient(),
) : IChannelTransport, AutoCloseable {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        // Frame `type`/`v` discriminators are declared with default values; without
        // this they'd be omitted on serialize, so the shim couldn't route the
        // outgoing hello/subagent frames (it replies with an Error and closes).
        encodeDefaults = true
    }

    private val _state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
    override val state: StateFlow<ChannelTransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 128)
    override val events: SharedFlow<ServerFrame> = _events.asSharedFlow()

    // Desktop's registries don't consume the chat frame stream; expose an empty
    // hot flow to satisfy the interface.
    private val _frameEvents = MutableSharedFlow<TransportFrameEvent>(extraBufferCapacity = 1)
    override val frameEvents: SharedFlow<TransportFrameEvent> = _frameEvents.asSharedFlow()

    private val pending = mutableMapOf<String, CompletableDeferred<ServerFrame>>()
    private val pendingMutex = Mutex()

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    private var connectJob: Job? = null

    override suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ) {
        connectJob?.cancel()
        connectJob = scope.launch { runSocket(baseShimUrl, token, deviceId, clientVersion) }
    }

    private suspend fun runSocket(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ) {
        _state.value = ChannelTransportState.Connecting()
        val wsUrl = baseShimUrl.trim().trimEnd('/').toWsUrl() + "/shim/v1/mobile"
        try {
            httpClient.webSocket(wsUrl) {
                session = this
                sendFrame(
                    HelloFrame(
                        id = randomId(),
                        ts = nowIso(),
                        token = token,
                        deviceId = deviceId,
                        clientVersion = clientVersion,
                    ),
                )
                for (frame in incoming) {
                    if (frame is Frame.Text) handleInbound(frame.readText())
                }
            }
            setDisconnected("socket closed")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            setDisconnected(error.message ?: "ws error")
        } finally {
            session = null
            failPending("ws disconnected")
        }
    }

    private suspend fun handleInbound(text: String) {
        val frame = runCatching { json.decodeFromString(ServerFrameSerializer, text) }.getOrNull() ?: return
        if (frame is ServerFrame.Welcome) {
            _state.value = ChannelTransportState.Connected(
                serverId = frame.serverId,
                sessionId = frame.sessionId,
                deviceId = frame.deviceId,
            )
        }
        frame.correlationId()?.let { requestId ->
            pendingMutex.withLock { pending.remove(requestId) }?.complete(frame)
        }
        _events.emit(frame)
    }

    override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse {
        val requestId = randomId()
        return awaitResponse(
            requestId,
            SubagentListFrame(id = randomId(), ts = nowIso(), requestId = requestId, all = all),
            timeoutMs,
        ) as ServerFrame.SubagentListResponse
    }

    override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long): ServerFrame.SubagentTodosResponse {
        val requestId = randomId()
        return awaitResponse(
            requestId,
            SubagentTodosFrame(id = randomId(), ts = nowIso(), requestId = requestId, toolCallId = toolCallId),
            timeoutMs,
        ) as ServerFrame.SubagentTodosResponse
    }

    private suspend fun awaitResponse(
        requestId: String,
        frame: ClientFrame,
        timeoutMs: Long,
    ): ServerFrame {
        val active = session
            ?: throw IllegalStateException("WS not connected (state=${_state.value::class.simpleName})")
        val deferred = CompletableDeferred<ServerFrame>()
        pendingMutex.withLock { pending[requestId] = deferred }
        return try {
            active.sendFrame(frame)
            withTimeout(timeoutMs.milliseconds) { deferred.await() }
        } finally {
            pendingMutex.withLock { pending.remove(requestId) }
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendFrame(frame: ClientFrame) {
        send(Frame.Text(frame.encodeJson(json)))
    }

    private fun setDisconnected(reason: String) {
        _state.value = ChannelTransportState.Disconnected(code = -1, reason = reason, willReconnect = false)
    }

    private suspend fun failPending(reason: String) {
        pendingMutex.withLock {
            pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
            pending.clear()
        }
    }

    override suspend fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        session = null
        setDisconnected("client disconnect")
    }

    override fun close() {
        connectJob?.cancel()
        httpClient.close()
    }

    // --- Unused on desktop: chat send/cancel/subscribe go over SSE, and cron /
    // a2ui use the HTTP APIs. These satisfy the interface but are never called. ---

    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
        startNewConversation: Boolean,
    ): Boolean = false

    override fun cancel(conversationId: String): Boolean = false
    override fun bye(): Boolean = false
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Failed
    override fun subscribe(runId: String, cursor: Long): Boolean = false

    override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) =
        unsupported("cron_list")

    override suspend fun sendCronAdd(
        agentId: String,
        name: String,
        description: String,
        prompt: String,
        recurring: Boolean,
        cron: String?,
        every: String?,
        at: String?,
        timezone: String?,
        conversationId: String?,
        timeoutMs: Long,
    ) = unsupported("cron_add")

    override suspend fun sendCronGet(taskId: String, timeoutMs: Long) = unsupported("cron_get")
    override suspend fun sendCronDelete(taskId: String, timeoutMs: Long) = unsupported("cron_delete")
    override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long) = unsupported("cron_delete_all")

    private fun unsupported(op: String): Nothing =
        throw UnsupportedOperationException("$op is not supported by the desktop WS side-channel; use the HTTP APIs")

    private fun randomId(): String = UUID.randomUUID().toString()
    private fun nowIso(): String = Instant.now().toString()

    private fun String.toWsUrl(): String = when {
        startsWith("https://") -> "wss://" + removePrefix("https://")
        startsWith("http://") -> "ws://" + removePrefix("http://")
        startsWith("ws://") || startsWith("wss://") -> this
        else -> "wss://$this"
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS

        fun defaultWsClient(): HttpClient = HttpClient(CIO) {
            engine {
                https {
                    trustManager = NativeTrustManager.trustManager
                }
            }
            install(WebSockets)
        }
    }
}

/** Pull the correlating `request_id` out of a response frame, when it has one. */
private fun ServerFrame.correlationId(): String? = when (this) {
    is ServerFrame.SubagentListResponse -> requestId
    is ServerFrame.SubagentTodosResponse -> requestId
    else -> null
}
