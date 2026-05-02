package com.letta.mobile.bot.protocol

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class WsBotClient(
    baseUrl: String,
    apiKey: String?,
) : BotClient, GatewayReadyClient, AutoCloseable {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpBaseUrl = normalizeHttpBaseUrl(baseUrl)
    private val webSocketUrl = normalizeWebSocketUrl(baseUrl)
    private val authToken = apiKey?.takeIf { it.isNotBlank() }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
        authToken?.let { bearerToken ->
            install(Auth) {
                bearer {
                    loadTokens { BearerTokens(bearerToken, bearerToken) }
                }
            }
        }
    }

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val requestMutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.CLOSED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var socketOpen = false

    @Volatile
    private var isUserClosing = false
    // letta-mobile-2psc: set by ensureSession() when it deliberately closes
    // the socket to switch agents. Consumed by the okhttp onClosed callback
    // so a clean code-1000 close doesn't get treated as an unexpected
    // disconnect (which would fail the next request and race with the
    // ensureSession-initiated reconnect).
    private var isSwitchingAgent = false

    /**
     * Sockets that ensureSession (or another internal path) has explicitly
     * retired. Any onClosed/onFailure callback originating from a socket
     * in this set is residual and must NOT be propagated as an unexpected
     * disconnect. Identity-based; uses Collections.newSetFromMap on a
     * weak-key IdentityHashMap so retired sockets are GC'd naturally.
     */
    private val retiredSockets: MutableSet<WebSocket> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    @Volatile
    private var activeAgentId: String? = null

    @Volatile
    private var activeConversationId: String? = null

    @Volatile
    private var activeSessionId: String? = null

    @Volatile
    private var activeRequestId: String? = null

    @Volatile
    private var activeRequestEvents: SendChannel<RequestSignal>? = null

    @Volatile
    private var openDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var sessionInitDeferred: CompletableDeferred<WsSessionInit>? = null

    @Volatile
    private var reconnectJob: kotlinx.coroutines.Job? = null

    override suspend fun sendMessage(request: BotChatRequest): BotChatResponse =
        streamMessage(request).toList().collectFinalResponse()

    override suspend fun ensureGatewayReady(
        agentId: String,
        conversationId: String?,
    ) {
        ensureSession(
            BotChatRequest(
                message = "",
                agentId = agentId,
                chatId = conversationId ?: "gateway-ready",
                conversationId = conversationId,
            )
        )
    }

    override fun streamMessage(request: BotChatRequest): Flow<BotStreamChunk> = flow {
        requestMutex.withLock {
            val requestId = UUID.randomUUID().toString()
            val requestChannel = Channel<RequestSignal>(capacity = Channel.UNLIMITED)

            try {
                ensureSession(request)
                activeRequestId = requestId
                activeRequestEvents = requestChannel
                _connectionState.value = ConnectionState.PROCESSING

                sendClientMessageWithRetry(
                    request = request,
                    message = WsClientMessage(
                        content = request.outboundContent(json),
                        requestId = requestId,
                        source = WsSource(
                            channel = "letta-mobile",
                            chatId = request.chatId ?: request.channelId ?: "api",
                        ),
                    ),
                )

                var finished = false
                while (!finished) {
                    when (val signal = requestChannel.receive()) {
                        is RequestSignal.Message -> {
                            when (val message = signal.message) {
                                is WsStreamEventMessage -> emit(
                                    message.toChunk(activeConversationId, activeAgentId)
                                        .requireValidTerminalShape("WsBotClient stream event")
                                )
                                is WsResultMessage -> {
                                    activeConversationId = message.conversationId ?: activeConversationId
                                    emit(
                                        BotStreamChunk(
                                            conversationId = activeConversationId,
                                            agentId = activeAgentId,
                                            requestId = message.requestId,
                                            aborted = message.aborted,
                                            done = true,
                                        ).requireValidTerminalShape("WsBotClient result frame")
                                    )
                                    finished = true
                                }

                                is WsErrorMessage -> {
                                    throw BotGatewayException(
                                        code = message.code.toGatewayErrorCode(),
                                        message = message.message,
                                    )
                                }

                                else -> Unit
                            }
                        }

                        is RequestSignal.Failure -> throw signal.throwable
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                activeRequestId = null
                activeRequestEvents = null
                requestChannel.close()
                if (!isUserClosing && socketOpen) {
                    _connectionState.value = ConnectionState.READY
                }
            }
        }
    }

    suspend fun abort() {
        val requestId = activeRequestId ?: return
        sendWebSocketMessage(WsAbortMessage(requestId = requestId))
    }

    override suspend fun getStatus(): BotStatusResponse {
        val response = httpClient.get("$httpBaseUrl/api/v1/status")
        if (response.status.value !in 200..299) {
            throw RuntimeException("Bot server error: ${response.status.value} ${response.bodyAsText()}")
        }
        return BotStatusResponseParser.parse(json, json.parseToJsonElement(response.bodyAsText()))
    }

    override suspend fun listAgents(): List<BotAgentInfo> {
        val response = httpClient.get("$httpBaseUrl/api/v1/agents")
        if (response.status.value !in 200..299) {
            throw RuntimeException("Bot server error: ${response.status.value} ${response.bodyAsText()}")
        }
        return response.body()
    }

    override fun close() {
        isUserClosing = true
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching {
            socket?.send(json.encodeToString(WsSessionCloseMessage.serializer(), WsSessionCloseMessage))
        }
        runCatching {
            socket?.close(1000, "client closing")
        }
        socket = null
        socketOpen = false
        openDeferred?.cancel()
        sessionInitDeferred?.cancel()
        activeRequestEvents?.close()
        httpClient.close()
        wsClient.dispatcher.executorService.shutdown()
        _connectionState.value = ConnectionState.CLOSED
        scope.cancel()
    }

    private suspend fun ensureSession(request: BotChatRequest) {
        connectionMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null

            val requestedAgentId = request.agentId ?: activeAgentId
                ?: throw BotGatewayException(
                    code = BotGatewayErrorCode.BAD_MESSAGE,
                    message = "WsBotClient requires request.agentId for session_start",
                )

            val requestedConversationId = request.conversationId
            val needsNewSession = !socketOpen || activeAgentId != requestedAgentId ||
                (requestedConversationId != null && requestedConversationId != activeConversationId)

            if (!needsNewSession) {
                return
            }

            if (socketOpen && activeAgentId != requestedAgentId) {
                // letta-mobile-2psc: arm the switching-agent flag BEFORE
                // we initiate the close so the okhttp onClosed listener
                // can suppress its handleUnexpectedDisconnect. Cleared in
                // openSocketLocked alongside isUserClosing.
                isSwitchingAgent = true
                sendWebSocketMessage(WsSessionCloseMessage)
                socket?.let { retiredSockets += it }
                socket?.close(1000, "switching agent")
                socket = null
                socketOpen = false
                activeConversationId = null
                activeSessionId = null
            }

            openSocketLocked()
            initializeSessionLocked(
                agentId = requestedAgentId,
                conversationId = requestedConversationId,
                forceNew = false,
            )
        }
    }

    private suspend fun openSocketLocked() {
        if (socketOpen) return

        _connectionState.value = if (activeAgentId == null) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
        isUserClosing = false
        isSwitchingAgent = false
        val deferred = CompletableDeferred<Unit>()
        openDeferred = deferred

        val requestBuilder = Request.Builder().url(webSocketUrl)
        authToken?.let {
            requestBuilder.header("X-Api-Key", it)
            requestBuilder.header("Authorization", "Bearer $it")
        }
        socket = wsClient.newWebSocket(requestBuilder.build(), listener)

        withTimeout(10_000) {
            deferred.await()
        }
    }

    private suspend fun initializeSessionLocked(
        agentId: String,
        conversationId: String?,
        forceNew: Boolean,
    ) {
        _connectionState.value = ConnectionState.CONNECTING
        val deferred = CompletableDeferred<WsSessionInit>()
        sessionInitDeferred = deferred

        sendWebSocketMessage(
            WsSessionStart(
                agentId = agentId,
                conversationId = conversationId,
                forceNew = forceNew,
            )
        )

        val sessionInit = try {
            withTimeout(10_000) {
                deferred.await()
            }
        } catch (t: Throwable) {
            throw when (t) {
                is BotGatewayException -> t
                else -> BotGatewayException(
                    code = BotGatewayErrorCode.SESSION_INIT_FAILED,
                    message = "Timed out waiting for session_init",
                    cause = t,
                )
            }
        }

        if (sessionInit.agentId != agentId) {
            throw BotGatewayException(
                code = BotGatewayErrorCode.BAD_MESSAGE,
                message = "Requested agent '$agentId' but session_init returned '${sessionInit.agentId}'",
            )
        }

        activeAgentId = sessionInit.agentId
        activeConversationId = sessionInit.conversationId
        activeSessionId = sessionInit.sessionId
        _connectionState.value = ConnectionState.READY
    }

    private suspend fun sendWebSocketMessage(payload: Any) {
        val webSocket = socket ?: throw BotGatewayException(
            code = BotGatewayErrorCode.NO_SESSION,
            message = "WebSocket is not connected",
        )

        val text = when (payload) {
            is WsSessionStart -> json.encodeToString(WsSessionStart.serializer(), payload)
            is WsClientMessage -> json.encodeToString(WsClientMessage.serializer(), payload)
            is WsAbortMessage -> json.encodeToString(WsAbortMessage.serializer(), payload)
            is WsSessionCloseMessage -> json.encodeToString(WsSessionCloseMessage.serializer(), payload)
            else -> error("Unsupported payload type: ${payload::class.qualifiedName}")
        }

        if (!webSocket.send(text)) {
            socketOpen = false
            throw BotGatewayException(
                code = BotGatewayErrorCode.STREAM_ERROR,
                message = "Failed to send WebSocket payload",
            )
        }
    }

    private suspend fun sendClientMessageWithRetry(
        request: BotChatRequest,
        message: WsClientMessage,
    ) {
        try {
            sendWebSocketMessage(message)
        } catch (error: BotGatewayException) {
            if (error.code != BotGatewayErrorCode.STREAM_ERROR) throw error

            connectionMutex.withLock {
                socket?.cancel()
                socket = null
                socketOpen = false
                openSocketLocked()
                initializeSessionLocked(
                    agentId = request.agentId ?: activeAgentId
                        ?: throw BotGatewayException(
                            code = BotGatewayErrorCode.BAD_MESSAGE,
                            message = "WsBotClient requires request.agentId for reconnect",
                        ),
                    conversationId = request.conversationId ?: activeConversationId,
                    forceNew = false,
                )
            }

            sendWebSocketMessage(message)
        }
    }

    private fun handleIncoming(text: String) {
        val message = try {
            parseIncoming(text)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse WebSocket message: ${text.take(160)}", e)
            return
        }

        when (message) {
            is WsSessionInit -> {
                activeAgentId = message.agentId
                activeConversationId = message.conversationId
                activeSessionId = message.sessionId
                sessionInitDeferred?.complete(message)
                _connectionState.value = ConnectionState.READY
            }

            is WsErrorMessage -> {
                val exception = BotGatewayException(
                    code = message.code.toGatewayErrorCode(),
                    message = message.message,
                )
                if (sessionInitDeferred?.isActive == true) {
                    sessionInitDeferred?.completeExceptionally(exception)
                } else {
                    routeToActiveRequest(message)
                }
                if (message.code == BotGatewayErrorCode.AUTH_FAILED.name) {
                    _connectionState.value = ConnectionState.CLOSED
                }
            }

            is WsStreamEventMessage,
            is WsResultMessage -> routeToActiveRequest(message)
        }
    }

    private fun routeToActiveRequest(message: WsInboundMessage) {
        val requestId = activeRequestId
        val target = activeRequestEvents ?: return

        val incomingRequestId = when (message) {
            is WsStreamEventMessage -> message.requestId
            is WsResultMessage -> message.requestId
            is WsErrorMessage -> message.requestId
            else -> null
        }

        if (requestId != null && incomingRequestId != null && incomingRequestId != requestId) {
            return
        }

        target.trySend(RequestSignal.Message(message))
    }

    /**
     * Returns true if [incoming] is a socket we've already retired or
     * replaced (e.g. the one ensureSession just closed during an agent
     * switch). Stale sockets can fire onFailure/onClosed callbacks on
     * okhttp's reader thread *after* we've swapped in their replacement;
     * treating those as "the current connection died" would incorrectly
     * kill the brand-new in-flight request. See letta-mobile-2psc.
     *
     * The check is two-pronged because there is a brief window in
     * ensureSession where `socket = null` (between close and the
     * subsequent openSocketLocked) — during that window we still need
     * to know that the incoming socket is the retired one.
     */
    private fun isResidualSocket(incoming: WebSocket?): Boolean {
        if (incoming == null) return false
        if (incoming in retiredSockets) return true
        val current = socket
        return current != null && incoming !== current
    }

    private fun handleUnexpectedDisconnect(cause: Throwable?) {
        socket = null
        socketOpen = false
        openDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(
            cause ?: BotGatewayException(BotGatewayErrorCode.STREAM_ERROR, "WebSocket closed unexpectedly")
        )

        if (activeRequestId != null) {
            activeRequestEvents?.trySend(
                RequestSignal.Failure(
                    cause ?: BotGatewayException(
                        code = BotGatewayErrorCode.STREAM_ERROR,
                        message = "WebSocket disconnected during request",
                    )
                )
            )
        }

        if (isUserClosing) {
            _connectionState.value = ConnectionState.CLOSED
            return
        }

        if (activeAgentId == null) {
            _connectionState.value = ConnectionState.CLOSED
            return
        }

        _connectionState.value = ConnectionState.RECONNECTING
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || isUserClosing) {
            return
        }

        val agentId = activeAgentId ?: return
        reconnectJob = scope.launch {
            val delaysMs = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
            var attempt = 0

            while (!isUserClosing) {
                val result = runCatching {
                    connectionMutex.withLock {
                        openSocketLocked()
                        initializeSessionLocked(
                            agentId = agentId,
                            conversationId = activeConversationId,
                            forceNew = false,
                        )
                    }
                }

                if (result.isSuccess) {
                    return@launch
                }

                kotlinx.coroutines.delay(delaysMs[minOf(attempt, delaysMs.lastIndex)])
                attempt++
            }
        }
    }

    private fun parseIncoming(text: String): WsInboundMessage {
        val element = json.parseToJsonElement(text)
        val obj = element as? JsonObject ?: throw SerializationException("Expected JSON object")
        val type = obj["type"]?.jsonPrimitive?.content ?: throw SerializationException("Missing type")

        return when (type) {
            "session_init" -> json.decodeFromJsonElement(WsSessionInit.serializer(), element)
            "stream" -> json.decodeFromJsonElement(WsStreamEventMessage.serializer(), element)
            "result" -> json.decodeFromJsonElement(WsResultMessage.serializer(), element)
            "error" -> json.decodeFromJsonElement(WsErrorMessage.serializer(), element)
            else -> throw SerializationException("Unknown WebSocket message type: $type")
        }
    }

    private fun normalizeWebSocketUrl(baseUrl: String): String {
        val uri = URI(baseUrl.trim())
        val scheme = when (uri.scheme?.lowercase()) {
            "http" -> "ws"
            "https" -> "wss"
            "ws", "wss" -> uri.scheme.lowercase()
            else -> throw IllegalArgumentException("Unsupported baseUrl scheme: ${uri.scheme}")
        }
        val path = uri.path.orEmpty().trimEnd('/').let { currentPath ->
            if (currentPath.endsWith(WS_PATH)) currentPath else "$currentPath$WS_PATH"
        }
        return URI(scheme, uri.userInfo, uri.host, uri.port, path, uri.query, uri.fragment).toString()
    }

    private fun normalizeHttpBaseUrl(baseUrl: String): String {
        val uri = URI(baseUrl.trim())
        val scheme = when (uri.scheme?.lowercase()) {
            "ws" -> "http"
            "wss" -> "https"
            "http", "https" -> uri.scheme.lowercase()
            else -> throw IllegalArgumentException("Unsupported baseUrl scheme: ${uri.scheme}")
        }
        val path = uri.path.orEmpty().trimEnd('/').removeSuffix(WS_PATH).ifBlank { "" }
        return URI(scheme, uri.userInfo, uri.host, uri.port, path.ifBlank { null }, null, null).toString().trimEnd('/')
    }

    private fun BotChatRequest.outboundContent(json: Json): String =
        contentItems?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(ListSerializer(BotMessageContentItem.serializer()), it) }
            ?: message

    private fun List<BotStreamChunk>.collectFinalResponse(): BotChatResponse {
        val assistantText = buildString {
            for (chunk in this@collectFinalResponse) {
                if (chunk.done) continue
                if (chunk.event == null || chunk.event == BotStreamEvent.ASSISTANT) {
                    append(chunk.text.orEmpty())
                }
            }
        }
        val terminal = lastOrNull { it.done } ?: lastOrNull()
        return BotChatResponse(
            response = assistantText,
            conversationId = terminal?.conversationId,
            agentId = terminal?.agentId,
        )
    }

    private fun String.toGatewayErrorCode(): BotGatewayErrorCode =
        runCatching { BotGatewayErrorCode.valueOf(this) }.getOrDefault(BotGatewayErrorCode.STREAM_ERROR)

    private fun WsStreamEventMessage.toChunk(
        conversationId: String?,
        agentId: String?,
    ): BotStreamChunk = BotStreamChunk(
        text = content,
        conversationId = conversationId,
        agentId = agentId,
        event = event,
        toolName = toolName,
        toolCallId = toolCallId,
        toolInput = toolInput,
        isError = isError,
        requestId = requestId,
        uuid = uuid,
        done = false,
    )

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socketOpen = true
            openDeferred?.complete(Unit)
            Log.i(TAG, "Connected to lettabot WebSocket gateway at $webSocketUrl")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleIncoming(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed ($code): $reason")
            // letta-mobile-2psc: residual callback from a socket we've
            // already replaced (e.g. via agent-switch). Ignore — the
            // current socket is healthy.
            if (isResidualSocket(webSocket)) {
                Log.d(TAG, "Ignoring onClosed from stale socket ($code: $reason)")
                return
            }
            // Deliberate agent-switch close initiated by ensureSession
            // also lands here; ensureSession is already reconnecting.
            if (isSwitchingAgent) {
                isSwitchingAgent = false
                socket = null
                socketOpen = false
                return
            }
            handleUnexpectedDisconnect(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // letta-mobile-2psc: residual EOF/IO failure from a socket we've
            // already replaced (typical race when ensureSession closes the
            // old socket and immediately opens a new one). The new socket
            // is already running; we must not fail the in-flight request
            // on the new socket because of the dying old one.
            if (isResidualSocket(webSocket)) {
                Log.d(TAG, "Ignoring onFailure from stale socket: ${t.message}")
                return
            }
            // Deliberate agent-switch close: okhttp can fire onFailure
            // (EOF) before onClosed during the close handshake.
            if (isSwitchingAgent) {
                Log.i(TAG, "WebSocket onFailure during agent switch — suppressed: ${t.message}")
                isSwitchingAgent = false
                socket = null
                socketOpen = false
                return
            }
            val failure = when {
                response?.code == 401 -> BotGatewayException(
                    code = BotGatewayErrorCode.AUTH_FAILED,
                    message = "WebSocket authentication failed",
                    cause = t,
                )

                else -> BotGatewayException(
                    code = BotGatewayErrorCode.STREAM_ERROR,
                    message = t.message ?: "WebSocket failure",
                    cause = t,
                )
            }
            Log.e(TAG, "WebSocket failure", failure)
            handleUnexpectedDisconnect(failure)
        }
    }

    private sealed interface RequestSignal {
        data class Message(val message: WsInboundMessage) : RequestSignal
        data class Failure(val throwable: Throwable) : RequestSignal
    }

    private sealed interface WsInboundMessage

    @Serializable
    private data class WsSessionStart(
        val type: String = "session_start",
        @SerialName("agent_id") val agentId: String,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("force_new") val forceNew: Boolean = false,
    )

    @Serializable
    private data class WsClientMessage(
        val type: String = "message",
        val content: String,
        @SerialName("request_id") val requestId: String,
        val source: WsSource,
    )

    @Serializable
    private data class WsSource(
        val channel: String,
        val chatId: String,
    )

    @Serializable
    private data class WsAbortMessage(
        val type: String = "abort",
        @SerialName("request_id") val requestId: String,
    )

    @Serializable
    private data object WsSessionCloseMessage {
        const val type: String = "session_close"
    }

    @Serializable
    private data class WsSessionInit(
        val type: String,
        @SerialName("agent_id") val agentId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("session_id") val sessionId: String,
    ) : WsInboundMessage

    @Serializable
    private data class WsStreamEventMessage(
        val type: String,
        val event: BotStreamEvent,
        val content: String? = null,
        @SerialName("tool_name") val toolName: String? = null,
        @SerialName("tool_call_id") val toolCallId: String? = null,
        @SerialName("tool_input") val toolInput: JsonElement? = null,
        @SerialName("is_error") val isError: Boolean = false,
        val uuid: String? = null,
        @SerialName("request_id") val requestId: String? = null,
    ) : WsInboundMessage

    @Serializable
    private data class WsResultMessage(
        val type: String,
        val success: Boolean,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("request_id") val requestId: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        val error: String? = null,
        val aborted: Boolean = false,
    ) : WsInboundMessage

    @Serializable
    private data class WsErrorMessage(
        val type: String,
        val code: String,
        val message: String,
        @SerialName("request_id") val requestId: String? = null,
    ) : WsInboundMessage

    companion object {
        private const val TAG = "WsBotClient"
        private const val WS_PATH = "/api/v1/agent-gateway"
    }
}
