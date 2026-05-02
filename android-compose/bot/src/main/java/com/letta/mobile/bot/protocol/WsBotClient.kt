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
import io.ktor.client.request.parameter
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
    // letta-mobile-flk.2: opt into the gateway's progressive tool-call
    // streaming mode (one frame per tool-call id with running snapshots
    // + a terminal completed frame). The default `false` matches the
    // gateway default (one frame per tool-call id), which is what
    // Matrix-style clients want. Mobile sets this true so the existing
    // dedup-by-id renderer can show running indicators incrementally.
    progressiveToolCalls: Boolean = false,
) : BotClient, GatewayReadyClient, AutoCloseable {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpBaseUrl = normalizeHttpBaseUrl(baseUrl)
    private val webSocketUrl = normalizeWebSocketUrl(baseUrl, progressiveToolCalls)
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

    /**
     * letta-mobile-w2hx.8: receive-side demux table. Keyed on
     * `conversation_id` (the routing primary key in the v2 protocol),
     * with `request_id` as a tiebreaker for the rare reconnection
     * window where the gateway emits a frame for a conv whose channel
     * has just been swapped. Lookup precedence is: (1) exact conv-id
     * hit; (2) the unique entry whose `requestId` matches; (3) drop.
     *
     * The map is intentionally not a `Map<conv, MutableSharedFlow>` —
     * the per-request `Channel` model gives us back-pressure and
     * exact-once semantics, which is what `streamMessage` needs. A
     * future bead can layer a hot SharedFlow on top for server-pushed
     * events that no caller awaits.
     */
    private val activeRoutes = java.util.concurrent.ConcurrentHashMap<String, RequestRoute>()

    /**
     * Routes that don't yet know their conversation_id (a fresh-route
     * stream where the gateway will return a brand-new conv on the
     * first chunk). They live here keyed by request_id until the first
     * inbound frame upgrades them into [activeRoutes].
     */
    private val pendingRoutes = java.util.concurrent.ConcurrentHashMap<String, RequestRoute>()

    private data class RequestRoute(
        val requestId: String,
        val channel: SendChannel<RequestSignal>,
        @Volatile var conversationId: String?,
    )

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
            val initialConvId = request.conversationId
            val route = RequestRoute(
                requestId = requestId,
                channel = requestChannel,
                conversationId = initialConvId,
            )

            // letta-mobile-w2hx.8 debug: trace inbound conv routing —
            // log the conv id we'll route by until/unless the gateway
            // upgrades it on the first chunk.
            Log.i(
                TAG,
                "w2hx8.streamMessage agent=${request.agentId} conv=${request.conversationId} " +
                    "active=$activeConversationId rid=${requestId.take(8)}",
            )
            try {
                ensureSession(request)
                if (initialConvId != null) {
                    activeRoutes[initialConvId] = route
                } else {
                    pendingRoutes[requestId] = route
                }
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
                                is WsStreamEventMessage -> {
                                    // letta-mobile-flk.5 / w2hx.8: when the
                                    // gateway emits a per-chunk
                                    // `conversation_id` we (a) update the
                                    // socket-level active conv pointer so a
                                    // subsequent reconnect resumes correctly
                                    // and (b) re-key this request's route
                                    // entry so further frames demux to the
                                    // same channel. The "fresh-route" path
                                    // arrives here as a pending route that
                                    // gets promoted into activeRoutes on
                                    // its first chunk.
                                    message.conversationId
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { incomingConv ->
                                            if (incomingConv != activeConversationId) {
                                                activeConversationId = incomingConv
                                            }
                                            promoteRoute(route, incomingConv)
                                        }
                                    emit(message.toChunk(activeConversationId, activeAgentId))
                                }
                                is WsResultMessage -> {
                                    message.conversationId
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { incomingConv ->
                                            activeConversationId = incomingConv
                                            promoteRoute(route, incomingConv)
                                        }
                                    emit(
                                        BotStreamChunk(
                                            conversationId = activeConversationId,
                                            agentId = activeAgentId,
                                            requestId = message.requestId,
                                            aborted = message.aborted,
                                            done = true,
                                        )
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
                releaseRoute(route)
                requestChannel.close()
                if (!isUserClosing && socketOpen) {
                    _connectionState.value = ConnectionState.READY
                }
            }
        }
    }

    /**
     * Move [route] from `pendingRoutes` into `activeRoutes` keyed on
     * [convId], or rekey it within `activeRoutes` if the conv id
     * changed mid-stream (gateway-side recovery in flk.5). Idempotent
     * when convId already matches.
     */
    private fun promoteRoute(route: RequestRoute, convId: String) {
        val previous = route.conversationId
        if (previous == convId) {
            // Hot path: conv id matches what we registered under. Make
            // sure activeRoutes still points at us in case a retired
            // entry collided.
            activeRoutes[convId] = route
            return
        }
        if (previous != null) {
            activeRoutes.remove(previous, route)
        }
        pendingRoutes.remove(route.requestId, route)
        route.conversationId = convId
        activeRoutes[convId] = route
    }

    /**
     * Drop [route] from both routing tables. Safe to call multiple
     * times; uses keyed-conditional removes so a stale entry doesn't
     * evict a successor that has reused the same key.
     */
    private fun releaseRoute(route: RequestRoute) {
        route.conversationId?.let { activeRoutes.remove(it, route) }
        pendingRoutes.remove(route.requestId, route)
    }

    suspend fun abort() {
        // letta-mobile-w2hx.8: with the single requestMutex still in
        // place there is at most one in-flight request at a time, so
        // "abort the current one" is well-defined. Pick the sole
        // active/pending route's requestId; if there is none, no-op.
        val requestId = soleInFlightRouteOrNull()?.requestId ?: return
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

    suspend fun browseFilesystem(path: String? = null, limit: Int = 500): BotFilesystemBrowseResponse {
        val response = httpClient.get("$httpBaseUrl/api/v1/filesystem/browse") {
            path?.takeIf { it.isNotBlank() }?.let { parameter("path", it) }
            parameter("limit", limit)
        }
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
        // letta-mobile-w2hx.8: close every routed request channel —
        // the streamMessage finally block will see CancellationException
        // on its next receive and clean up.
        activeRoutes.values.forEach { it.channel.close() }
        activeRoutes.clear()
        pendingRoutes.values.forEach { it.channel.close() }
        pendingRoutes.clear()
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
            // letta-mobile-w2hx.7: re-init when (a) the socket isn't open,
            // (b) the agent is different, or (c) the requested conversation
            // differs from the active one. Freshness no longer needs a
            // dedicated flag: a "New chat" tap surfaces here as
            // `requestedConversationId == null` while `activeConversationId`
            // holds the previous chat's conv id, which trips this same
            // branch and we re-init with no conv id (the gateway then
            // creates a fresh Letta conversation).
            val needsNewSession = !socketOpen ||
                activeAgentId != requestedAgentId ||
                requestedConversationId != activeConversationId

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
            } else if (socketOpen && activeAgentId == requestedAgentId) {
                // Same agent but a different (or absent) conv: close the
                // active session frame and start a new one in-place. The
                // socket itself stays up.
                sendWebSocketMessage(WsSessionCloseMessage)
                activeConversationId = null
                activeSessionId = null
            }

            openSocketLocked()
            initializeSessionLocked(
                agentId = requestedAgentId,
                conversationId = requestedConversationId,
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
    ) {
        _connectionState.value = ConnectionState.CONNECTING
        val deferred = CompletableDeferred<WsSessionInit>()
        sessionInitDeferred = deferred

        sendWebSocketMessage(
            WsSessionStart(
                agentId = agentId,
                conversationId = conversationId,
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
                    routeInbound(message)
                }
                if (message.code == BotGatewayErrorCode.AUTH_FAILED.name) {
                    _connectionState.value = ConnectionState.CLOSED
                }
            }

            is WsStreamEventMessage,
            is WsResultMessage -> routeInbound(message)
        }
    }

    /**
     * letta-mobile-w2hx.8: demux an inbound frame to the right
     * in-flight request. Lookup precedence:
     *
     *   1. `conversation_id` exact match in [activeRoutes].
     *   2. `request_id` match against any active or pending route
     *      (covers (a) fresh-route streams whose conv id hasn't been
     *      announced yet, and (b) reconnection windows where the
     *      route has been rekeyed but a stray frame still carries the
     *      old conv id).
     *   3. Drop on the floor with a debug log.
     *
     * Today this is mostly belt-and-suspenders — the `requestMutex`
     * still serializes streams — but it makes the demux structural so
     * a future change to support concurrent streams (server-side
     * multiplex, second socket per agent) plugs in without touching
     * this boundary.
     */
    private fun routeInbound(message: WsInboundMessage) {
        val incomingConvId = when (message) {
            is WsStreamEventMessage -> message.conversationId
            is WsResultMessage -> message.conversationId
            // letta-mobile-w2hx.8: gateway error frames don't carry a
            // conversation_id today (see lettabot src/api/ws-gateway.ts);
            // we fall back to request_id matching for those.
            else -> null
        }
        val incomingRequestId = when (message) {
            is WsStreamEventMessage -> message.requestId
            is WsResultMessage -> message.requestId
            is WsErrorMessage -> message.requestId
            else -> null
        }

        val byConv = incomingConvId?.let { activeRoutes[it] }
        val byRequestId = if (byConv == null && incomingRequestId != null) {
            activeRoutes.values.firstOrNull { it.requestId == incomingRequestId }
                ?: pendingRoutes[incomingRequestId]
        } else null

        val target = byConv ?: byRequestId
        if (target == null) {
            // Best-effort fallback: if there is exactly one in-flight
            // route across both maps, route to it. This mirrors the
            // pre-w2hx.8 single-active-request behavior for frames the
            // gateway emits without a conversation_id (older event
            // shapes) and keeps regression risk bounded.
            val sole = soleInFlightRouteOrNull()
            if (sole != null) {
                sole.channel.trySend(RequestSignal.Message(message))
            } else {
                Log.d(TAG, "w2hx8.routeInbound drop conv=$incomingConvId rid=$incomingRequestId")
            }
            return
        }
        target.channel.trySend(RequestSignal.Message(message))
    }

    private fun soleInFlightRouteOrNull(): RequestRoute? {
        val activeCount = activeRoutes.size
        val pendingCount = pendingRoutes.size
        return when {
            activeCount + pendingCount != 1 -> null
            activeCount == 1 -> activeRoutes.values.firstOrNull()
            else -> pendingRoutes.values.firstOrNull()
        }
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

        // letta-mobile-w2hx.8: fan the disconnect out to every
        // in-flight route so each waiting streamMessage caller fails
        // its own collector. trySend is safe even when the channel is
        // unbounded; failures here are best-effort.
        val failure = RequestSignal.Failure(
            cause ?: BotGatewayException(
                code = BotGatewayErrorCode.STREAM_ERROR,
                message = "WebSocket disconnected during request",
            )
        )
        activeRoutes.values.forEach { it.channel.trySend(failure) }
        pendingRoutes.values.forEach { it.channel.trySend(failure) }

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

    private fun normalizeWebSocketUrl(
        baseUrl: String,
        progressiveToolCalls: Boolean = false,
    ): String {
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
        // letta-mobile-flk.2: append `progressive_tool_calls=1` if the
        // caller opted in. Merge with any existing query rather than
        // clobbering, so a baseUrl carrying its own query params (e.g.
        // a debug tracer flag) is preserved. Skip if the flag is
        // already present so this is idempotent.
        val mergedQuery = mergeQuery(uri.query, progressiveToolCalls)
        return URI(scheme, uri.userInfo, uri.host, uri.port, path, mergedQuery, uri.fragment).toString()
    }

    private fun mergeQuery(existing: String?, progressiveToolCalls: Boolean): String? {
        if (!progressiveToolCalls) return existing
        val flag = "progressive_tool_calls=1"
        if (existing.isNullOrBlank()) return flag
        // Idempotent: don't append twice.
        val parts = existing.split('&')
        val alreadyPresent = parts.any { it.equals(flag, ignoreCase = true) ||
            it.startsWith("progressive_tool_calls=", ignoreCase = true) }
        return if (alreadyPresent) existing else "$existing&$flag"
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
        // letta-mobile-flk.5: prefer the per-frame conversation_id when
        // the gateway provides it. Newer lettabot builds echo the active
        // Letta conv id on every chunk so mid-stream conversation swaps
        // (after auto-recovery from a stuck conv) are observable BEFORE
        // the terminal `result` frame; older builds populate only the
        // result frame, so we fall back to the cached
        // `activeConversationId` to preserve the previous behavior.
        conversationId = this.conversationId ?: conversationId,
        agentId = agentId,
        event = event,
        toolName = toolName,
        toolCallId = toolCallId,
        toolInput = toolInput,
        isError = isError,
        requestId = requestId,
        uuid = uuid,
        oldConversationId = oldConversationId,
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
        /**
         * letta-mobile-flk.5: present when the gateway echoes the active
         * Letta conversation id on each per-chunk frame, OR when the
         * gateway emits an explicit `conversation_swap` event (in which
         * case this carries the *new* conversation id). Falling back to
         * the connection-level `activeConversationId` when absent
         * preserves bug-for-bug behavior with older gateway builds.
         */
        @SerialName("conversation_id") val conversationId: String? = null,
        /**
         * letta-mobile-flk.5: present only on
         * `event == CONVERSATION_SWAP` — the conversation id the gateway
         * abandoned. Allows the receiver to migrate stranded optimistic
         * locals from the old timeline before re-pointing the observer.
         */
        @SerialName("old_conversation_id") val oldConversationId: String? = null,
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
