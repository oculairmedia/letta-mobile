package com.letta.mobile.bot.protocol

import android.util.Log
import com.letta.mobile.data.a2ui.A2uiFrameEvent
import com.letta.mobile.data.model.ToolCall
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
import okhttp3.ConnectionPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
    private val streamReceiveTimeoutMs: Long = DEFAULT_STREAM_RECEIVE_TIMEOUT_MS,
    private val reconnectDelaysMs: List<Long> = DEFAULT_RECONNECT_DELAYS_MS,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
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
        // Explicit pool: WsBotClient typically holds 1 active WS connection
        // (rarely 2 during agent switches). 3 idle max with 90s keep-alive
        // matches reconnect cadence without over-allocating sockets.
        .connectionPool(ConnectionPool(3, 90, TimeUnit.SECONDS))
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val requestMutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.CLOSED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _a2uiEvents = MutableSharedFlow<A2uiFrameEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val a2uiEvents: SharedFlow<A2uiFrameEvent> = _a2uiEvents.asSharedFlow()

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
            // letta-mobile-7o8a: The gateway evicts idle sessions after 5
            // minutes while keeping the WS socket open. When a subsequent
            // message triggers NO_SESSION, we re-initialize the session on
            // the same socket (awaiting session_init) and retry the message
            // exactly once with a fresh request_id.
            val maxSessionRetries = 1

            val requestChannel = Channel<RequestSignal>(capacity = Channel.UNLIMITED)
            val initialConvId = request.conversationId
            var activeRoute = RequestRoute(
                requestId = UUID.randomUUID().toString(),
                channel = requestChannel,
                conversationId = initialConvId,
            )

            // letta-mobile-w2hx.8 debug: trace inbound conv routing —
            // log the conv id we'll route by until/unless the gateway
            // upgrades it on the first chunk.
            Log.i(
                TAG,
                "w2hx8.streamMessage agent=${request.agentId} conv=${request.conversationId} " +
                    "active=$activeConversationId rid=${activeRoute.requestId.take(8)}",
            )
            try {
                ensureSession(request)
                if (initialConvId != null) {
                    activeRoutes[initialConvId] = activeRoute
                } else {
                    pendingRoutes[activeRoute.requestId] = activeRoute
                }
                _connectionState.value = ConnectionState.PROCESSING

                sendClientMessageWithRetry(
                    request = request,
                    message = WsClientMessage(
                        content = request.outboundContent(json),
                        requestId = activeRoute.requestId,
                        source = WsSource(
                            channel = "letta-mobile",
                            chatId = request.chatId ?: request.channelId ?: "api",
                        ),
                    ),
                )
                activeRoute.firstFrameSent = true

                var finished = false
                var sessionRetries = 0

                while (!finished) {
                    val signal = try {
                        withTimeout(streamReceiveTimeoutMs) {
                            requestChannel.receive()
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        Log.w(
                            TAG,
                            "Timed out waiting for WebSocket stream response " +
                                "rid=${activeRoute.requestId.take(8)} timeoutMs=$streamReceiveTimeoutMs",
                        )
                        runCatching { sendWebSocketMessage(WsAbortMessage(requestId = activeRoute.requestId)) }
                        throw BotGatewayException(
                            code = BotGatewayErrorCode.STREAM_ERROR,
                            message = "Timed out waiting for WebSocket stream response",
                            cause = timeout,
                        )
                    }
                    when (signal) {
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
                                            promoteRoute(activeRoute, incomingConv)
                                        }
                                    emit(message.toChunk(activeConversationId, activeAgentId))
                                }
                                is WsResultMessage -> {
                                    message.conversationId
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { incomingConv ->
                                            activeConversationId = incomingConv
                                            promoteRoute(activeRoute, incomingConv)
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
                                    val errorCode = message.code.toGatewayErrorCode()
                                    if (errorCode == BotGatewayErrorCode.NO_SESSION && sessionRetries < maxSessionRetries) {
                                        val requestedConversationId = request.conversationId
                                        sessionRetries++
                                        Log.i(
                                            TAG,
                                            "w2hx8.NO_SESSION retry=$sessionRetries agent=${request.agentId} " +
                                                "conv=${request.conversationId}",
                                        )
                                        recoverSession(request)
                                        if (requestedConversationId != null &&
                                            activeConversationId != null &&
                                            activeConversationId != requestedConversationId
                                        ) {
                                            Log.w(
                                                TAG,
                                                "NO_SESSION recovery resumed a different conversation " +
                                                    "requested=$requestedConversationId active=$activeConversationId",
                                            )
                                        }
                                        releaseRoute(activeRoute)
                                        activeRoute = RequestRoute(
                                            requestId = UUID.randomUUID().toString(),
                                            channel = requestChannel,
                                            conversationId = initialConvId,
                                        )
                                        if (initialConvId != null) {
                                            activeRoutes[initialConvId] = activeRoute
                                        } else {
                                            pendingRoutes[activeRoute.requestId] = activeRoute
                                        }
                                        sendWebSocketMessage(
                                            WsClientMessage(
                                                content = request.outboundContent(json),
                                                requestId = activeRoute.requestId,
                                                source = WsSource(
                                                    channel = "letta-mobile",
                                                    chatId = request.chatId ?: request.channelId ?: "api",
                                                ),
                                            )
                                        )
                                    } else {
                                        throw BotGatewayException(
                                            code = errorCode,
                                            message = message.message,
                                        )
                                    }
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
                releaseRoute(activeRoute)
                requestChannel.close()
                if (!isUserClosing && socketOpen) {
                    _connectionState.value = ConnectionState.READY
                }
            }
        }
    }

    /**
     * letta-mobile-7o8a: Re-initialize the gateway session on the current
     * socket after a NO_SESSION error. The socket is still open (the
     * gateway only evicted the application-level session, not the
     * transport). Uses [initializeSessionLocked] to send `session_start`
     * and await `session_init` before returning, guaranteeing the gateway
     * is ready for the next message.
     */
    private suspend fun recoverSession(request: BotChatRequest) {
        connectionMutex.withLock {
            val agentId = request.agentId ?: activeAgentId
                ?: throw BotGatewayException(
                    code = BotGatewayErrorCode.BAD_MESSAGE,
                    message = "WsBotClient requires request.agentId for session resume",
                )
            // Clear session state so initializeSessionLocked sees a
            // mismatch and sends session_start, even though the socket
            // is still open and the agent/conv IDs may match.
            activeConversationId = null
            activeSessionId = null
            initializeSessionLocked(
                agentId = agentId,
                conversationId = request.conversationId,
                forceNew = false,
            )
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

    override suspend fun abort() {
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
            socket?.close(1000, "client closing")
        }
        runCatching {
            socket?.cancel()
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
            // Re-init when the socket is absent, the target agent changes,
            // the requested conversation changes, or the caller explicitly
            // requests a fresh conversation. The forceNew branch is critical:
            // null conversation_id alone can be interpreted by the gateway/SDK
            // as "resume the active session".
            val needsNewSession = !socketOpen ||
                activeAgentId != requestedAgentId ||
                requestedConversationId != activeConversationId ||
                request.forceNew

            if (!needsNewSession) {
                return
            }

            if (socketOpen && activeAgentId != requestedAgentId) {
                // letta-mobile-2psc: arm the switching-agent flag BEFORE
                // we initiate the close so the okhttp onClosed listener
                // can suppress its handleUnexpectedDisconnect. Cleared in
                // openSocketLocked alongside isUserClosing.
                isSwitchingAgent = true
                sendWebSocketMessage(WsSessionCloseMessage())
                socket?.let { retiredSockets += it }
                socket?.close(1000, "switching agent")
                socket = null
                socketOpen = false
                activeConversationId = null
                activeSessionId = null
            } else if (socketOpen && activeAgentId == requestedAgentId) {
                // Same agent but a different/fresh conv: close the active
                // session frame and start a new one in-place. The socket
                // itself stays up; force_new rides on the next session_start.
                sendWebSocketMessage(WsSessionCloseMessage())
                activeConversationId = null
                activeSessionId = null
            }

            openSocketLocked()
            initializeSessionLocked(
                agentId = requestedAgentId,
                conversationId = requestedConversationId,
                forceNew = request.forceNew,
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
            parseIncoming(json, text)
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

            is WsA2uiMessage -> {
                _a2uiEvents.tryEmit(message.toA2uiEvent())
            }
        }
    }

    private fun WsA2uiMessage.toA2uiEvent(): A2uiFrameEvent = A2uiFrameEvent(
        transport = "lettabot-gateway",
        frameId = null,
        timestamp = null,
        agentId = agentId ?: activeAgentId,
        conversationId = conversationId ?: activeConversationId,
        turnId = null,
        runId = null,
        requestId = requestId,
        messages = messages,
    )

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
        // Only consider routes whose first frame has been sent — routes that
        // are registered but haven't sent their initial WsClientMessage would
        // cause abort() to fire for a requestId the gateway hasn't seen yet.
        val active = activeRoutes.values.singleOrNull()?.takeIf { it.firstFrameSent }
        val pending = pendingRoutes.values.singleOrNull()?.takeIf { it.firstFrameSent }
        return when {
            active != null && pending == null -> active
            pending != null && activeRoutes.isEmpty() -> pending
            else -> null
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
            var attempt = 0
            var lastFailure: Throwable? = null

            while (!isUserClosing && attempt < maxReconnectAttempts) {
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

                lastFailure = result.exceptionOrNull()
                val delayMs = reconnectDelaysMs.getOrElse(attempt) { reconnectDelaysMs.lastOrNull() ?: 0L }
                if (delayMs > 0) {
                    delay(delayMs)
                }
                attempt++
            }

            if (!isUserClosing) {
                val failure = BotGatewayException(
                    code = BotGatewayErrorCode.STREAM_ERROR,
                    message = "WebSocket reconnect failed after $maxReconnectAttempts attempts",
                    cause = lastFailure,
                )
                Log.e(TAG, failure.message, failure)
                activeRoutes.values.forEach { it.channel.trySend(RequestSignal.Failure(failure)) }
                pendingRoutes.values.forEach { it.channel.trySend(RequestSignal.Failure(failure)) }
                connectionMutex.withLock {
                    socket?.cancel()
                    socket = null
                    socketOpen = false
                    openDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(failure)
                    sessionInitDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(failure)
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        }
    }

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

    companion object {
        private const val TAG = "WsBotClient"
        private const val DEFAULT_STREAM_RECEIVE_TIMEOUT_MS = 300_000L
        private val DEFAULT_RECONNECT_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        private const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 6
    }
}
