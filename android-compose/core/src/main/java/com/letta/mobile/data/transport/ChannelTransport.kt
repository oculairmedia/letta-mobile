package com.letta.mobile.data.transport

import android.util.Log
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface A2uiActionDispatchResult {
    data class Sent(val frameId: String) : A2uiActionDispatchResult
    data class Queued(val frameId: String) : A2uiActionDispatchResult
    data object Failed : A2uiActionDispatchResult
}

/**
 * letta-mobile-9vgk: client for the admin-shim's `/shim/v1/mobile`
 * WebSocket. Wire contract: `admin-shim/docs/MOBILE_WS_PROTOCOL.md`.
 *
 * **Lifecycle:** call [connect] with a base shim URL + token; the
 * client opens the WS, sends `hello`, and surfaces every server frame
 * via [events] in arrival order. Call [send] to dispatch a turn,
 * [cancel] to abort the current run, [bye] to close cleanly,
 * [disconnect] to tear down without a polite shutdown.
 *
 * **Single-flight:** mirrors the server's per-session guard
 * (spec §4.6) — [send] returns false while a turn is in flight rather
 * than letting it round-trip into a guaranteed `protocol_violation`.
 *
 * **Cache invalidation:** [state] flips to [State.Connected] with the
 * fresh `welcome.server_id`; callers that have a previously-cached id
 * must compare and invalidate per spec §1.
 *
 * **Reconnection** is the caller's responsibility (Phase 1 by design).
 * On any close the client lands in [State.Disconnected]; nothing here
 * auto-redials.
 */
internal fun defaultChannelTransportScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Singleton
class ChannelTransport internal constructor(
    private val scope: CoroutineScope,
) : IChannelTransport {
    @Inject
    constructor() : this(defaultChannelTransportScope())

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Connected(
            val serverId: String,
            val sessionId: String,
            val deviceId: String?,
            // Welcome (§2.2) reports a2uiNegotiated independently from
            // the resolved version/catalog handle; chip wiring reads
            // these directly without unwrapping a nullable container.
            val a2uiEnabled: Boolean = false,
            val a2uiVersion: String? = null,
            val a2uiCatalog: String? = null,
            // Populated when the post-welcome a2ui_capabilities frame
            // arrives (§2.2). Informational — null until then.
            val a2uiSupportedCatalogs: List<String> = emptyList(),
            val a2uiSupportedWidgets: List<String> = emptyList(),
        ) : State

        /**
         * Closed cleanly or due to failure. `code`/`reason` come from
         * the WebSocket close frame when available; failure paths
         * surface a synthetic code (-1) and the throwable message.
         */
        data class Disconnected(
            val code: Int,
            val reason: String,
            val isAuthFailure: Boolean = false,
        ) : State
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    // OkHttp HTTP client tuned for a long-lived WS — no read timeout
    // (the server pings every 25s; a finite read timeout would force
    // a needless close on idle), generous connect timeout for slow
    // mobile networks, and HTTP/1.1 only (the shim doesn't speak h2
    // over WS upgrade).
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(45, TimeUnit.SECONDS) // TCP-level ping; defense-in-depth alongside server-side WS pings
            .build()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    override val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Hot stream of every parsed server frame. SharedFlow with replay=0
     * because the caller (typically a chat ViewModel) needs the events
     * routed live; replaying old frames after a re-subscribe would
     * trigger duplicate UI side-effects.
     */
    private val _events = MutableSharedFlow<ServerFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<ServerFrame> = _events.asSharedFlow()

    /**
     * Single-flight guard. Set true on accepted [send], cleared by
     * the inbound pump when a `turn_done` arrives. Mirrors the
     * server's `inFlight` so we don't bother round-tripping rejections.
     */
    @Volatile private var inFlight: Boolean = false

    /**
     * Latest `run_id` observed during the current turn — captured from
     * the first non-`turn_started` frame (spec §2.2: `turn_started`
     * doesn't carry `run_id`, but every subsequent frame does). Used
     * by [cancel] which requires `run_id` per §2.1.
     */
    private val currentRunId = AtomicReference<String?>(null)
    private val currentTurnId = AtomicReference<String?>(null)

    private val socketRef = AtomicReference<WebSocket?>(null)
    private val socketMutex = Mutex()
    private var listenerJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastConnectionConfig: ConnectionConfig? = null
    private val pendingA2uiActionLock = Any()
    private val pendingA2uiActions = ArrayDeque<UserActionFrame>()

    // letta-mobile-d52f.1: request_id correlation for cron WS round-trips.
    // Each suspend send helper installs a CompletableDeferred keyed by
    // request_id; the inbound handler completes the matching deferred
    // when the response arrives. On disconnect the pending map is
    // cancelled so callers don't wait forever.
    private val pendingCronRequests = ConcurrentHashMap<String, CompletableDeferred<ServerFrame>>()

    init {
        scope.coroutineContext.job.invokeOnCompletion {
            clearPendingA2uiActions(reason = "session ended")
            socketRef.getAndSet(null)?.close(NORMAL_CLOSE, "session ended")
            listenerJob?.cancel()
            reconnectJob?.cancel()
            cancelPendingCronRequests("session ended")
            _state.value = State.Disconnected(NORMAL_CLOSE, "session ended")
        }
    }

    // letta-mobile-ns5l: tracks who triggered the close so onClosed can
    // log initiator alongside the wire code. Set true in disconnect()
    // and teardownLocked() before the close call; cleared in
    // onClosed/onFailure after the disposed state lands.
    @Volatile private var clientInitiatedClose: Boolean = false

    /**
     * Open the WebSocket and send `hello`. Suspends until the request
     * is dispatched; success or failure of the handshake itself is
     * observable on [state] (Connected on welcome, Disconnected on
     * close).
     */
    override suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ): Unit = socketMutex.withLock {
        lastConnectionConfig = ConnectionConfig(
            baseShimUrl = baseShimUrl,
            token = token,
            deviceId = deviceId,
            clientVersion = clientVersion,
        )
        // Drop any previous socket — caller is allowed to call
        // connect() repeatedly (e.g. after a switch); each call
        // supersedes the prior one.
        teardownLocked(reason = "reconnect")
        _state.value = State.Connecting
        currentRunId.set(null)
        currentTurnId.set(null)
        inFlight = false

        val wsUrl = baseShimUrl.trimEnd('/').toWsUrl() + "/shim/v1/mobile"
        val request = Request.Builder().url(wsUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    HelloFrame(
                        id = UUID.randomUUID().toString(),
                        ts = nowIso(),
                        token = token,
                        deviceId = deviceId,
                        clientVersion = clientVersion,
                    ).encodeJson(json)
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleInbound(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // letta-mobile-ns5l: server-initiated close. Log before
                // we ack so the timing window between server FIN and
                // client ack is visible in adb logcat for diagnosis.
                Log.i(
                    TAG,
                    "WS closing (server-initiated) code=$code reason=${reason.ifEmpty { "<empty>" }} " +
                        "inFlight=$inFlight runId=${currentRunId.get()}",
                )
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val initiator = if (clientInitiatedClose) "client" else "server"
                Log.i(
                    TAG,
                    "WS closed code=$code reason=${reason.ifEmpty { "<empty>" }} initiator=$initiator " +
                        "inFlight=$inFlight runId=${currentRunId.get()}",
                )
                if (!socketRef.compareAndSet(webSocket, null)) {
                    Log.i(TAG, "Ignoring close from superseded WS socket")
                    return
                }
                _state.value = State.Disconnected(code, reason)
                inFlight = false
                currentRunId.set(null)
                currentTurnId.set(null)
                clientInitiatedClose = false
                cancelPendingCronRequests("WS closed: code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                Log.w(TAG, "WS failure: ${t.message} (httpCode=$httpCode)", t)
                if (!socketRef.compareAndSet(webSocket, null)) {
                    Log.i(TAG, "Ignoring failure from superseded WS socket")
                    return
                }
                val isAuth = httpCode == 401 || httpCode == 403
                _state.value = State.Disconnected(
                    code = httpCode ?: -1,
                    reason = t.message ?: t::class.java.simpleName,
                    isAuthFailure = isAuth,
                )
                inFlight = false
                currentRunId.set(null)
                currentTurnId.set(null)
                clientInitiatedClose = false
                cancelPendingCronRequests("WS failure: ${t.message ?: t::class.java.simpleName}")
            }
        }

        socketRef.set(httpClient.newWebSocket(request, listener))
    }

    /**
     * Dispatch a user turn. Returns false if a previous turn is still
     * in flight (mirrors the server's single-flight rejection so we
     * don't burn a round trip on a guaranteed error). Returns false
     * before [State.Connected] for the same reason.
     */
    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        // lcp-dlj: multimodal payload. When non-null, the shim ignores [text]
        // and treats this as the authoritative content. Caller is responsible
        // for shape — see [SendMessageFrame.contentParts] for the contract.
        contentParts: JsonArray?,
    ): Boolean {
        if (state.value !is State.Connected) return false
        if (inFlight) return false
        val socket = socketRef.get() ?: return false
        inFlight = true
        currentRunId.set(null)
        currentTurnId.set(null)
        return socket.sendFrame(
            SendMessageFrame(
                id = UUID.randomUUID().toString(),
                ts = nowIso(),
                agentId = agentId,
                conversationId = conversationId,
                text = text,
                otid = otid,
                contentParts = contentParts,
            )
        )
    }

    /**
     * Cancel the in-flight run. Returns false if no `run_id` has been
     * captured yet (e.g. cancel between `turn_started` and the first
     * post-start frame) — the spec's no-implicit-fallback rule means
     * sending without `run_id` is a guaranteed `protocol_violation`,
     * so we surface the failure locally instead.
     */
    override fun cancel(): Boolean {
        clearPendingA2uiActions(reason = "user cancel")
        val socket = socketRef.get() ?: return false
        val rid = currentRunId.get() ?: return false
        return socket.sendFrame(
            CancelFrame(
                id = UUID.randomUUID().toString(),
                ts = nowIso(),
                runId = rid,
            )
        )
    }

    /**
     * Polite shutdown: send `bye` and let the server close.
     */
    override fun bye(): Boolean {
        val socket = socketRef.get() ?: return false
        return socket.sendFrame(
            ByeFrame(
                id = UUID.randomUUID().toString(),
                ts = nowIso(),
            )
        )
    }

    // ─── Cron WS helpers (letta-mobile-d52f.1, sister to lcp-d5g) ────
    //
    // Each helper generates a request_id, sends the frame, and suspends
    // until the matching response arrives (or the socket disconnects /
    // the [DEFAULT_CRON_TIMEOUT_MS] elapses). The completed response
    // also flows through [events] so observers — including the repo
    // layer — see the same frame.

    /**
     * List scheduled tasks. Filters are optional; pass null to receive
     * every task the shim knows about.
     */
    override suspend fun sendCronList(
        agentId: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronListResponse {
        val requestId = newCronRequestId()
        val frame = CronListFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            agentId = agentId,
            conversationId = conversationId,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.CronListResponse
    }

    /**
     * Add a scheduled prompt. The selector (`cron` / `every` / `at`) is
     * decided by the caller — exactly one should be non-null. The shim
     * normalizes all three into the persisted task's `cron` field.
     */
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
    ): ServerFrame.CronAddResponse {
        val requestId = newCronRequestId()
        val frame = CronAddFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            agentId = agentId,
            name = name,
            description = description,
            prompt = prompt,
            recurring = recurring,
            cron = cron,
            every = every,
            at = at,
            timezone = timezone,
            conversationId = conversationId,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.CronAddResponse
    }

    override suspend fun sendCronGet(
        taskId: String,
        timeoutMs: Long,
    ): ServerFrame.CronGetResponse {
        val requestId = newCronRequestId()
        val frame = CronGetFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            taskId = taskId,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.CronGetResponse
    }

    override suspend fun sendCronDelete(
        taskId: String,
        timeoutMs: Long,
    ): ServerFrame.CronDeleteResponse {
        val requestId = newCronRequestId()
        val frame = CronDeleteFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            taskId = taskId,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.CronDeleteResponse
    }

    override suspend fun sendCronDeleteAll(
        agentId: String,
        timeoutMs: Long,
    ): ServerFrame.CronDeleteAllResponse {
        val requestId = newCronRequestId()
        val frame = CronDeleteAllFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            agentId = agentId,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.CronDeleteAllResponse
    }

    /** Generate a fresh UUID-prefixed request_id for cron correlation. */
    private fun newCronRequestId(): String = "cron-${UUID.randomUUID()}"

    /**
     * Register a deferred for the request_id, send the frame, and wait
     * for the response (or the configured timeout). Failures (socket
     * down, send returned false) surface as [IllegalStateException] so
     * callers can branch on offline vs success/error-response.
     */
    private suspend fun awaitCronResponse(
        requestId: String,
        frame: ClientFrame,
        timeoutMs: Long,
    ): ServerFrame {
        val socket = socketRef.get()
            ?: throw IllegalStateException("Cron send failed: no socket")
        if (state.value !is State.Connected) {
            throw IllegalStateException("Cron send failed: state=${state.value::class.simpleName}")
        }
        val deferred = CompletableDeferred<ServerFrame>()
        val previous = pendingCronRequests.put(requestId, deferred)
        previous?.cancel()
        try {
            val ok = socket.sendFrame(frame)
            if (!ok) {
                pendingCronRequests.remove(requestId, deferred)
                throw IllegalStateException("Cron send failed: sendFrame returned false")
            }
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingCronRequests.remove(requestId, deferred)
            throw e
        } catch (e: Throwable) {
            pendingCronRequests.remove(requestId, deferred)
            throw e
        }
    }

    private fun cancelPendingCronRequests(reason: String) {
        val snapshot = pendingCronRequests.keys.toList()
        snapshot.forEach { requestId ->
            pendingCronRequests.remove(requestId)?.cancel(CancellationException(reason))
        }
    }

    /**
     * Tear down without sending `bye`. Intended for shutdown paths
     * where the caller doesn't care about a clean server-side close
     * (e.g. process exit, account switch).
     */
    override suspend fun disconnect(): Unit = socketMutex.withLock {
        clearPendingA2uiActions(reason = "client disconnect")
        teardownLocked(reason = "client disconnect")
    }

    private fun teardownLocked(reason: String) {
        clientInitiatedClose = true
        Log.i(TAG, "WS teardown (client-initiated) reason=$reason inFlight=$inFlight runId=${currentRunId.get()}")
        socketRef.getAndSet(null)?.close(NORMAL_CLOSE, reason)
        listenerJob?.cancel()
        listenerJob = null
        if (_state.value !is State.Disconnected) {
            _state.value = State.Disconnected(NORMAL_CLOSE, reason)
        }
        inFlight = false
        currentRunId.set(null)
        currentTurnId.set(null)
        cancelPendingCronRequests("WS teardown: $reason")
    }

    private fun handleInbound(text: String) {
        val frame = runCatching {
            json.decodeFromString(ServerFrameSerializer, text)
        }.getOrElse {
            Log.w(TAG, "Failed to parse WS frame: ${it.message}", it)
            return
        }

        when (frame) {
            is ServerFrame.Welcome -> {
                _state.value = State.Connected(
                    serverId = frame.serverId,
                    sessionId = frame.sessionId,
                    deviceId = frame.deviceId,
                    a2uiEnabled = frame.a2uiNegotiated,
                    a2uiVersion = frame.a2ui?.version,
                    a2uiCatalog = frame.a2ui?.catalogId,
                )
                drainPendingA2uiActions()
            }

            is ServerFrame.A2uiCapabilities -> {
                // Fold the post-welcome capability advertisement into
                // the existing Connected state so the renderer registry
                // can clamp itself.
                (_state.value as? State.Connected)?.let { current ->
                    _state.value = current.copy(
                        a2uiSupportedCatalogs = frame.supportedCatalogs,
                        a2uiSupportedWidgets = frame.supportedWidgets,
                    )
                }
            }

            is ServerFrame.UserActionAck -> {
                // Inbound ack — no state mutation needed today. Logging
                // for visibility; surface to the renderer when the UX
                // requires it (queued/rejected feedback, etc.).
                if (frame.status != "accepted") {
                    Log.w(TAG, "user_action ${frame.actionId} status=${frame.status} reason=${frame.reason}")
                }
            }

            is ServerFrame.UserActionOutcome -> {
                Log.i(
                    TAG,
                    "user_action outcome frameId=${frame.frameId} outcome=${frame.outcome} " +
                        "reason=${frame.reason ?: "<none>"}",
                )
            }

            is ServerFrame.TurnStarted -> {
                // lcp-99a: the shim now pre-creates the Run synchronously
                // before emitting turn_started, so run_id is always present
                // on the very first frame of the turn. Capture it eagerly
                // so cancel() works from the first frame the device sees,
                // without the "between turn_started and first run-bearing
                // frame" dead zone.
                currentRunId.set(frame.runId)
                currentTurnId.set(frame.turnId)
                drainPendingA2uiActions()
            }

            is ServerFrame.TurnDone -> {
                inFlight = false
                currentRunId.set(null)
                currentTurnId.set(null)
            }

            is ServerFrame.Error -> {
                // Errors that close the socket are reported via the
                // listener's onClosed/onFailure separately. For the
                // soft-error case (single-flight, missing fields,
                // run_not_found) we keep the connection up; just
                // forward the frame so the caller can surface it.
            }

            is ServerFrame.CronListResponse,
            is ServerFrame.CronAddResponse,
            is ServerFrame.CronGetResponse,
            is ServerFrame.CronDeleteResponse,
            is ServerFrame.CronDeleteAllResponse -> {
                // letta-mobile-d52f.1: route to the awaiting suspend
                // helper keyed by request_id. The frame still falls
                // through to the broadcast emit below so observers see
                // it too.
                frame.cronRequestIdOrNull()?.let { rid ->
                    pendingCronRequests.remove(rid)?.complete(frame)
                }
            }

            else -> {
                // Capture run_id from the first frame that has one.
                // Most server frames after turn_started carry it.
                val rid = frame.runIdOrNull()
                if (rid != null && currentRunId.get() == null) {
                    currentRunId.set(rid)
                }
                val tid = frame.turnIdOrNull()
                if (tid != null && currentTurnId.get() == null) {
                    currentTurnId.set(tid)
                }
                if (rid != null) {
                    drainPendingA2uiActions()
                }
            }
        }

        scope.launch { _events.emit(frame) }
    }

    private fun WebSocket.sendFrame(frame: ClientFrame): Boolean {
        return send(frame.encodeJson(json))
    }

    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult {
        val socket = socketRef.get()
        val stateNow = state.value
        val frame = action.toUserActionFrame().withActiveRoutingFallback()
        if (frame.runId == null) {
            Log.w(
                TAG,
                "user_action missing run_id; queueing until active run is known " +
                    "surfaceId=${action.surfaceId} event=${action.name} frameId=${frame.id}",
            )
            return enqueueA2uiAction(frame).also { result ->
                if (stateNow !is State.Connected || socket == null) {
                    requestReconnectIfQueued(result, "missing run_id and no live socket")
                }
            }
        }
        if (stateNow is State.Connected && socket != null) {
            val ok = socket.sendFrame(frame)
            if (ok) {
                Log.i(
                    TAG,
                    "user_action sent surfaceId=${action.surfaceId} event=${action.name} frameId=${frame.id}",
                )
                // letta-mobile-lwmo diagnostic: dump the resolved context so we
                // can see what reached the wire when payloads look wrong.
                Log.d(TAG, "user_action context payload=${json.encodeToString(JsonObject.serializer(), frame.context)}")
                return A2uiActionDispatchResult.Sent(frame.id)
            }
            Log.w(
                TAG,
                "user_action sendFrame returned false; queueing surfaceId=${action.surfaceId} event=${action.name}",
            )
            return enqueueA2uiAction(frame).also { requestReconnectIfQueued(it, "sendFrame returned false") }
        }
        Log.w(
            TAG,
            "user_action no live socket (state=${stateNow::class.simpleName} socketNull=${socket == null}); " +
                "queueing surfaceId=${action.surfaceId} event=${action.name}",
        )
        return enqueueA2uiAction(frame).also { requestReconnectIfQueued(it, "no live socket") }
    }

    private fun enqueueA2uiAction(frame: UserActionFrame): A2uiActionDispatchResult =
        synchronized(pendingA2uiActionLock) {
            if (pendingA2uiActions.size >= MAX_PENDING_A2UI_ACTIONS) {
                A2uiActionDispatchResult.Failed
            } else {
                pendingA2uiActions.addLast(frame)
                A2uiActionDispatchResult.Queued(frame.id)
            }
        }

    private fun requeueA2uiActionFirst(frame: UserActionFrame) {
        synchronized(pendingA2uiActionLock) {
            pendingA2uiActions.addFirst(frame)
        }
    }

    private fun clearPendingA2uiActions(reason: String) {
        val dropped = synchronized(pendingA2uiActionLock) {
            val size = pendingA2uiActions.size
            pendingA2uiActions.clear()
            size
        }
        if (dropped > 0) {
            Log.i(TAG, "dropped $dropped queued user_action frame(s): $reason")
        }
    }

    private fun requestReconnectIfQueued(result: A2uiActionDispatchResult, reason: String) {
        if (result !is A2uiActionDispatchResult.Queued) return
        requestReconnect(reason)
    }

    private fun requestReconnect(reason: String) {
        val config = lastConnectionConfig
        if (config == null) {
            Log.w(TAG, "user_action queued but reconnect unavailable: no prior connection config")
            return
        }
        if (state.value is State.Connecting) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            Log.i(TAG, "redialing WS for queued user_action: $reason")
            connect(
                baseShimUrl = config.baseShimUrl,
                token = config.token,
                deviceId = config.deviceId,
                clientVersion = config.clientVersion,
            )
        }
    }

    private fun drainPendingA2uiActions() {
        while (true) {
            val queuedFrame = synchronized(pendingA2uiActionLock) {
                if (pendingA2uiActions.isEmpty()) null else pendingA2uiActions.removeFirst()
            } ?: return
            val frame = queuedFrame.withActiveRoutingFallback()
            if (frame.runId == null) {
                requeueA2uiActionFirst(frame)
                return
            }
            val socket = socketRef.get()
            if (state.value !is State.Connected || socket == null || !socket.sendFrame(frame)) {
                requeueA2uiActionFirst(frame)
                return
            }
        }
    }

    private fun A2uiAction.toUserActionFrame(): UserActionFrame =
        UserActionFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            name = name,
            surfaceId = surfaceId,
            context = context,
            runId = runId,
            turnId = turnId,
            actionId = actionId,
        )

    private fun UserActionFrame.withActiveRoutingFallback(): UserActionFrame = copy(
        runId = runId ?: currentRunId.get(),
        turnId = turnId ?: currentTurnId.get(),
    )

    private data class ConnectionConfig(
        val baseShimUrl: String,
        val token: String,
        val deviceId: String,
        val clientVersion: String,
    )

    companion object {
        private const val TAG = "ChannelTransport"
        private const val NORMAL_CLOSE = 1000
        private const val MAX_PENDING_A2UI_ACTIONS = 16

        /**
         * Default await-window for a cron WS round-trip. Matches the
         * 5s ceiling the shim's own integration tests use; tunable
         * per-call via the helper's `timeoutMs` parameter.
         */
        const val DEFAULT_CRON_TIMEOUT_MS: Long = 5_000L

        internal fun nowIso(): String = Instant.now().toString()

        internal fun String.toWsUrl(): String = when {
            startsWith("https://") -> "wss://" + removePrefix("https://")
            startsWith("http://") -> "ws://" + removePrefix("http://")
            startsWith("ws://") || startsWith("wss://") -> this
            else -> "wss://$this"
        }

        private fun ServerFrame.runIdOrNull(): String? = when (this) {
            is ServerFrame.AssistantMessage -> runId
            is ServerFrame.ReasoningMessage -> runId
            is ServerFrame.ToolCallMessage -> runId
            is ServerFrame.ToolReturnMessage -> runId
            is ServerFrame.StopReason -> runId
            is ServerFrame.UsageStatistics -> runId
            is ServerFrame.TurnDone -> runId
            is ServerFrame.Error -> runId
            is ServerFrame.A2ui -> runId
            else -> null
        }

        private fun ServerFrame.turnIdOrNull(): String? = when (this) {
            is ServerFrame.TurnStarted -> turnId
            is ServerFrame.AssistantMessage -> turnId
            is ServerFrame.ReasoningMessage -> turnId
            is ServerFrame.ToolCallMessage -> turnId
            is ServerFrame.ToolReturnMessage -> turnId
            is ServerFrame.StopReason -> turnId
            is ServerFrame.UsageStatistics -> turnId
            is ServerFrame.TurnDone -> turnId
            is ServerFrame.Error -> turnId
            is ServerFrame.A2ui -> turnId
            else -> null
        }

        // letta-mobile-d52f.1: pull the request_id off whichever cron
        // response variant arrived so [awaitCronResponse] can complete
        // the matching deferred.
        private fun ServerFrame.cronRequestIdOrNull(): String? = when (this) {
            is ServerFrame.CronListResponse -> requestId
            is ServerFrame.CronAddResponse -> requestId
            is ServerFrame.CronGetResponse -> requestId
            is ServerFrame.CronDeleteResponse -> requestId
            is ServerFrame.CronDeleteAllResponse -> requestId
            else -> null
        }
    }
}
