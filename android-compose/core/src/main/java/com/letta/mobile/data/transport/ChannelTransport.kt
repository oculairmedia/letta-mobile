package com.letta.mobile.data.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
@Singleton
class ChannelTransport @Inject constructor() {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Connected(
            val serverId: String,
            val sessionId: String,
            val deviceId: String?,
            val a2uiNegotiation: com.letta.mobile.data.a2ui.A2uiNegotiation? = null,
        ) : State

        /**
         * Closed cleanly or due to failure. `code`/`reason` come from
         * the WebSocket close frame when available; failure paths
         * surface a synthetic code (-1) and the throwable message.
         */
        data class Disconnected(val code: Int, val reason: String) : State
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

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
    val events: SharedFlow<ServerFrame> = _events.asSharedFlow()

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

    private val socketRef = AtomicReference<WebSocket?>(null)
    private val socketMutex = Mutex()
    private var listenerJob: Job? = null

    /**
     * Open the WebSocket and send `hello`. Suspends until the request
     * is dispatched; success or failure of the handshake itself is
     * observable on [state] (Connected on welcome, Disconnected on
     * close).
     */
    suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ): Unit = socketMutex.withLock {
        // Drop any previous socket — caller is allowed to call
        // connect() repeatedly (e.g. after a switch); each call
        // supersedes the prior one.
        teardownLocked(reason = "reconnect")
        _state.value = State.Connecting
        currentRunId.set(null)
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
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = State.Disconnected(code, reason)
                inFlight = false
                currentRunId.set(null)
                socketRef.compareAndSet(webSocket, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}", t)
                _state.value = State.Disconnected(-1, t.message ?: t::class.java.simpleName)
                inFlight = false
                currentRunId.set(null)
                socketRef.compareAndSet(webSocket, null)
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
    fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String? = null,
        // lcp-dlj: multimodal payload. When non-null, the shim ignores [text]
        // and treats this as the authoritative content. Caller is responsible
        // for shape — see [SendMessageFrame.contentParts] for the contract.
        contentParts: JsonArray? = null,
    ): Boolean {
        if (state.value !is State.Connected) return false
        if (inFlight) return false
        val socket = socketRef.get() ?: return false
        inFlight = true
        currentRunId.set(null)
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
    fun cancel(): Boolean {
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
    fun bye(): Boolean {
        val socket = socketRef.get() ?: return false
        return socket.sendFrame(
            ByeFrame(
                id = UUID.randomUUID().toString(),
                ts = nowIso(),
            )
        )
    }

    /**
     * Tear down without sending `bye`. Intended for shutdown paths
     * where the caller doesn't care about a clean server-side close
     * (e.g. process exit, account switch).
     */
    suspend fun disconnect(): Unit = socketMutex.withLock {
        teardownLocked(reason = "client disconnect")
    }

    private fun teardownLocked(reason: String) {
        socketRef.getAndSet(null)?.close(NORMAL_CLOSE, reason)
        listenerJob?.cancel()
        listenerJob = null
        if (_state.value !is State.Disconnected) {
            _state.value = State.Disconnected(NORMAL_CLOSE, reason)
        }
        inFlight = false
        currentRunId.set(null)
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
                    a2uiNegotiation = frame.a2uiNegotiation,
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
            }

            is ServerFrame.TurnDone -> {
                inFlight = false
                currentRunId.set(null)
            }

            is ServerFrame.Error -> {
                // Errors that close the socket are reported via the
                // listener's onClosed/onFailure separately. For the
                // soft-error case (single-flight, missing fields,
                // run_not_found) we keep the connection up; just
                // forward the frame so the caller can surface it.
            }

            else -> {
                // Capture run_id from the first frame that has one.
                // Most server frames after turn_started carry it.
                val rid = frame.runIdOrNull()
                if (rid != null && currentRunId.get() == null) {
                    currentRunId.set(rid)
                }
            }
        }

        scope.launch { _events.emit(frame) }
    }

    private fun WebSocket.sendFrame(frame: ClientFrame): Boolean {
        return send(frame.encodeJson(json))
    }

    companion object {
        private const val TAG = "ChannelTransport"
        private const val NORMAL_CLOSE = 1000

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
    }
}
