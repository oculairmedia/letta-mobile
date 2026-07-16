package com.letta.mobile.data.transport

import android.util.Log
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.timeline.ConversationCursorStore
import com.letta.mobile.data.timeline.NoOpConversationCursorStore
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * client for the admin-shim's `/shim/v1/mobile` WebSocket.
 * Delegates responsibilities to:
 * - [PerConversationStateManager] (in-flight/turn bookkeeping)
 * - [CronRequestCorrelator] (cron request/response mapping)
 * - [CursorResumeCoordinator] (resuming runs, sequence cursor tracking)
 * - [WebSocketConnection] (OkHttp socket lifecycle & reconnect redialing)
 */
internal fun defaultChannelTransportScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Singleton
class ChannelTransport internal constructor(
    private val scope: CoroutineScope,
    private val cursorStore: RunCursorStore,
    private val conversationCursorStore: ConversationCursorStore,
) : IChannelTransport {
    @Inject
    constructor(
        cursorStore: RunCursorStore,
        conversationCursorStore: ConversationCursorStore,
    ) : this(
        defaultChannelTransportScope(),
        cursorStore,
        conversationCursorStore,
    )

    constructor(cursorStore: RunCursorStore) : this(
        defaultChannelTransportScope(),
        cursorStore,
        NoOpConversationCursorStore,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val _state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
    override val state: StateFlow<ChannelTransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<ServerFrame> = _events.asSharedFlow()

    private val _frameEvents = MutableSharedFlow<TransportFrameEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frameEvents: SharedFlow<TransportFrameEvent> = _frameEvents.asSharedFlow()

    private val conversationStateManager = PerConversationStateManager()
    private val cronCorrelator = CronRequestCorrelator()
    private val cursorCoordinator = CursorResumeCoordinator(scope, cursorStore, conversationCursorStore, json)
    private val connection = WebSocketConnection(scope, json)

    private val socketMutex = Mutex()
    private val pendingA2uiActionLock = Any()

    init {
        scope.coroutineContext.job.invokeOnCompletion {
            clearPendingA2uiActions(reason = "session ended")
            connection.disconnect(reason = "session ended")
            cronCorrelator.cancelPendingRequests("session ended")
            _state.value = ChannelTransportState.Disconnected(NORMAL_CLOSE, "session ended")
        }
    }

    private fun requireConversationKey(conversationId: String): String? =
        conversationId.trim().takeIf { it.isNotEmpty() }

    override suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ): Unit = socketMutex.withLock {
        teardownLocked(reason = "reconnect")
        _state.value = ChannelTransportState.Connecting()
        conversationStateManager.clearAllTurnState()
        cursorCoordinator.ensureLoaded()
        val helloResume = cursorCoordinator.loadHelloResumeCursors()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    HelloFrame(
                        id = UUID.randomUUID().toString(),
                        ts = nowIso(),
                        token = token,
                        deviceId = deviceId,
                        clientVersion = clientVersion,
                        resume = helloResume.takeIf { it.isNotEmpty() },
                    ).encodeJson(json)
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleInbound(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(
                    TAG,
                    "WS closing (server-initiated) code=$code reason=${reason.ifEmpty { "<empty>" }} " +
                        "activeConversations=${conversationStateManager.activeConversationCount()}",
                )
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasClientInitiatedClose = connection.clientInitiatedClose
                val initiator = if (wasClientInitiatedClose) "client" else "server"
                Log.i(
                    TAG,
                    "WS closed code=$code reason=${reason.ifEmpty { "<empty>" }} initiator=$initiator " +
                        "activeConversations=${conversationStateManager.activeConversationCount()}",
                )
                if (!connection.compareAndSetSuperseded(webSocket)) {
                    Log.i(TAG, "Ignoring close from superseded WS socket")
                    return
                }
                val shouldReconnect = shouldReconnectOnClose(code, wasClientInitiatedClose)
                if (shouldReconnect && code == KEEPALIVE_PONG_TIMEOUT_CLOSE_CODE) {
                    Telemetry.event(
                        "ChannelTransport", "keepalive.pongTimeout",
                        "code" to code,
                        "reason" to reason,
                    )
                }
                _state.value = ChannelTransportState.Disconnected(
                    code = code,
                    reason = reason,
                    willReconnect = shouldReconnect,
                )
                cronCorrelator.cancelPendingRequests("WS closed: code=$code reason=$reason")
                if (shouldReconnect) {
                    requestReconnect("WS closed: code=$code reason=$reason")
                } else {
                    conversationStateManager.clearAllTurnState()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                Log.w(TAG, "WS failure: ${t.message} (httpCode=$httpCode)", t)
                if (!connection.compareAndSetSuperseded(webSocket)) {
                    Log.i(TAG, "Ignoring failure from superseded WS socket")
                    return
                }
                val isAuth = httpCode == 401 || httpCode == 403
                val shouldReconnect = !isAuth
                _state.value = ChannelTransportState.Disconnected(
                    code = httpCode ?: -1,
                    reason = t.message ?: t::class.java.simpleName,
                    isAuthFailure = isAuth,
                    willReconnect = shouldReconnect,
                )
                cronCorrelator.cancelPendingRequests("WS failure: ${t.message ?: t::class.java.simpleName}")
                if (shouldReconnect) {
                    requestReconnect("WS failure: ${t.message ?: t::class.java.simpleName}")
                } else {
                    conversationStateManager.clearAllTurnState()
                }
            }
        }

        connection.connect(baseShimUrl, token, deviceId, clientVersion, listener)
    }

    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
        startNewConversation: Boolean,
    ): Boolean {
        val normalizedAgentId = agentId.trim()
        val normalizedConversationId = conversationId.trim()
        val hasPayload = text.isNotBlank() || contentParts?.isNotEmpty() == true
        val conversationIdAllowed = normalizedConversationId.isNotEmpty() || startNewConversation
        if (normalizedAgentId.isEmpty() || !conversationIdAllowed || !hasPayload) {
            Log.w(
                TAG,
                "Rejecting malformed send_message locally: " +
                    "agentIdPresent=${normalizedAgentId.isNotEmpty()} " +
                    "conversationIdPresent=${normalizedConversationId.isNotEmpty()} " +
                    "startNewConversation=$startNewConversation " +
                    "hasPayload=$hasPayload",
            )
            return false
        }
        if (state.value !is ChannelTransportState.Connected) return false
        val conversationKey = if (startNewConversation) {
            NEW_CONVERSATION_STATE_KEY
        } else {
            requireConversationKey(normalizedConversationId) ?: return false
        }
        val perConv = conversationStateManager.stateForConversation(conversationKey)
        if (!perConv.inFlight.compareAndSet(false, true)) return false
        perConv.currentRunId.set(null)
        perConv.currentTurnId.set(null)
        val sent = connection.sendFrame(
            SendMessageFrame(
                id = UUID.randomUUID().toString(),
                ts = nowIso(),
                agentId = normalizedAgentId,
                conversationId = normalizedConversationId,
                startNewConversation = startNewConversation,
                text = text,
                otid = otid,
                contentParts = contentParts,
            )
        )
        if (!sent) {
            conversationStateManager.clearConversationTurnState(conversationKey)
        }
        return sent
    }

    override fun cancel(conversationId: String): Boolean {
        val conversationKey = requireConversationKey(conversationId) ?: return false
        clearPendingA2uiActions(conversationKey, reason = "user cancel")
        val rid = conversationStateManager.currentRunId(conversationKey) ?: return false
        cursorCoordinator.markUserCancelled(conversationKey, rid)
        val sent = connection.sendFrame(
            CancelFrame(
                id = UUID.randomUUID().toString(),
                ts = nowIso(),
                runId = rid,
            )
        )
        if (sent) {
            conversationStateManager.clearRunConversation(rid)
            conversationStateManager.clearConversationTurnState(conversationKey)
        }
        return sent
    }

    override fun bye(): Boolean {
        return connection.sendFrame(
            ByeFrame(
                id = UUID.randomUUID().toString(),
                ts = nowIso(),
            )
        )
    }

    override fun subscribe(runId: String, cursor: Long): Boolean {
        if (runId.isEmpty()) return false
        if (cursor < 0L) return false
        if (state.value !is ChannelTransportState.Connected) return false
        return connection.sendFrame(
            SubscribeFrame(
                id = UUID.randomUUID().toString(),
                ts = nowIso(),
                runId = runId,
                cursor = cursor,
            )
        )
    }

    override suspend fun sendCronList(
        agentId: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronListResponse {
        val requestId = cronCorrelator.newCronRequestId()
        val frame = CronListFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            agentId = agentId,
            conversationId = conversationId,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.CronListResponse
    }

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
        val requestId = cronCorrelator.newCronRequestId()
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
        val requestId = cronCorrelator.newCronRequestId()
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
        val requestId = cronCorrelator.newCronRequestId()
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
        val requestId = cronCorrelator.newCronRequestId()
        val frame = CronDeleteAllFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            agentId = agentId,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.CronDeleteAllResponse
    }

    override suspend fun sendSubagentList(
        all: Boolean,
        timeoutMs: Long,
    ): ServerFrame.SubagentListResponse {
        val requestId = cronCorrelator.newCronRequestId()
        val frame = SubagentListFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            all = all,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.SubagentListResponse
    }

    override suspend fun sendSubagentTodos(
        toolCallId: String,
        timeoutMs: Long,
    ): ServerFrame.SubagentTodosResponse {
        val requestId = cronCorrelator.newCronRequestId()
        val frame = SubagentTodosFrame(
            id = UUID.randomUUID().toString(),
            ts = nowIso(),
            requestId = requestId,
            toolCallId = toolCallId,
        )
        return awaitCronResponse(requestId, frame, timeoutMs) as ServerFrame.SubagentTodosResponse
    }

    private suspend fun awaitCronResponse(
        requestId: String,
        frame: ClientFrame,
        timeoutMs: Long,
    ): ServerFrame {
        if (state.value !is ChannelTransportState.Connected) {
            throw IllegalStateException("Cron send failed: state=${state.value::class.simpleName}")
        }
        return cronCorrelator.awaitCronResponse(
            requestId = requestId,
            sendFrame = { connection.sendFrame(frame) },
            timeoutMs = timeoutMs
        )
    }

    override suspend fun disconnect(): Unit = socketMutex.withLock {
        clearPendingA2uiActions(reason = "client disconnect")
        teardownLocked(reason = "client disconnect")
    }

    private fun teardownLocked(reason: String) {
        connection.teardown(reason)
        if (_state.value !is ChannelTransportState.Disconnected) {
            _state.value = ChannelTransportState.Disconnected(NORMAL_CLOSE, reason)
        }
        conversationStateManager.clearAllTurnState()
        cronCorrelator.cancelPendingRequests("WS teardown: $reason")
    }

    private fun handleInbound(text: String, isReplay: Boolean = false) {
        val frame = runCatching {
            json.decodeFromString(ServerFrameSerializer, text)
        }.getOrElse {
            Log.w(TAG, "Failed to parse WS frame: ${it.message}", it)
            return
        }

        cursorCoordinator.recordCursorFromEnvelope(text, conversationStateManager::activeConversationForRun)
        cursorCoordinator.recordHelloResumeReplayTelemetry(
            frame, text,
            conversationIdOrNull = { it.conversationIdOrNull() },
            seqOrNull = { it.seqOrNull() }
        )

        when (frame) {
            is ServerFrame.Welcome -> {
                connection.resetReconnectBackoff()
                _state.value = ChannelTransportState.Connected(
                    serverId = frame.serverId,
                    sessionId = frame.sessionId,
                    deviceId = frame.deviceId,
                    a2uiEnabled = frame.a2uiNegotiated,
                    a2uiVersion = frame.a2ui?.version,
                    a2uiCatalog = frame.a2ui?.catalogId,
                    canonicalLiveTransport = frame.canonicalLiveTransport,
                )
                conversationStateManager.conversationIds().forEach(::drainPendingA2uiActions)
                cursorCoordinator.resumeActiveRuns(::subscribe) { state.value::class.java.simpleName }
            }

            is ServerFrame.A2uiCapabilities -> {
                (_state.value as? ChannelTransportState.Connected)?.let { current ->
                    _state.value = current.copy(
                        a2uiSupportedCatalogs = frame.supportedCatalogs,
                        a2uiSupportedWidgets = frame.supportedWidgets,
                    )
                }
            }

            is ServerFrame.UserActionAck -> {
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
                val perConv = conversationStateManager.stateForConversation(frame.conversationId)
                perConv.inFlight.set(true)
                perConv.currentRunId.set(frame.runId)
                perConv.currentTurnId.set(frame.turnId)
                conversationStateManager.recordRunConversation(frame.runId, frame.conversationId)
                conversationStateManager.clearConversationTurnState(NEW_CONVERSATION_STATE_KEY)
                drainPendingA2uiActions(frame.conversationId)
            }

            is ServerFrame.TurnDone -> {
                val convId = conversationStateManager.activeConversationForRun(frame.runId)
                    ?: cursorCoordinator.getResumedRunConversationId(frame.runId)
                if (convId != null) cursorCoordinator.clearCursor(convId, frame.runId)
                cursorCoordinator.removeResumedRun(frame.runId)
                conversationStateManager.clearRunConversation(frame.runId)
                convId?.let(conversationStateManager::clearConversationTurnState)
            }

            is ServerFrame.Error -> {
                if (frame.code == CURSOR_EXPIRED_ERROR_CODE) {
                    cursorCoordinator.clearExpiredCursor(frame) { state.value::class.java.simpleName }
                }
            }

            is ServerFrame.CronListResponse,
            is ServerFrame.CronAddResponse,
            is ServerFrame.CronGetResponse,
            is ServerFrame.CronDeleteResponse,
            is ServerFrame.CronDeleteAllResponse,
            is ServerFrame.SubagentListResponse,
            is ServerFrame.SubagentTodosResponse -> {
                frame.cronRequestIdOrNull()?.let { rid ->
                    cronCorrelator.completeRequest(rid, frame)
                }
            }

            is ServerFrame.SubscribeFrameMessage -> {
                cursorCoordinator.getResumedRunConversationId(frame.runId)?.let { convId ->
                    cursorCoordinator.recordCursor(convId, frame.runId, frame.seq)
                }
                val innerFrame = frame.frame.withProtocolTypeAlias()
                val innerText = innerFrame.toString()
                runCatching {
                    json.decodeFromString(ServerFrameSerializer, innerText)
                }.onSuccess { inner ->
                    handleInbound(innerText, isReplay = true)
                    Log.d(
                        TAG,
                        "subscribe_frame unwrapped runId=${frame.runId} seq=${frame.seq} " +
                            "innerType=${inner::class.java.simpleName}",
                    )
                }.onFailure { t ->
                    Log.w(
                        TAG,
                        "subscribe_frame inner decode failed runId=${frame.runId} seq=${frame.seq}: ${t.message}",
                        t,
                    )
                }
            }

            is ServerFrame.SubscribeDone -> {
                cursorCoordinator.clearResumedRunFromAllActive(frame.runId)
                Log.i(
                    TAG,
                    "subscribe_done runId=${frame.runId} lastSeq=${frame.lastSeq} status=${frame.status}",
                )
            }

            else -> {
                val rid = frame.runIdOrNull()
                val tid = frame.turnIdOrNull()
                val convId = frame.conversationIdOrNull() ?: conversationStateManager.activeConversationForRun(rid)
                if (rid != null && convId != null) {
                    conversationStateManager.recordRunConversation(rid, convId)
                    val perConv = conversationStateManager.stateForConversation(convId)
                    if (perConv.currentRunId.get() == null) {
                        perConv.currentRunId.set(rid)
                    }
                    if (tid != null && perConv.currentTurnId.get() == null) {
                        perConv.currentTurnId.set(tid)
                    }
                    drainPendingA2uiActions(convId)
                }
            }
        }

        scope.launch {
            _events.emit(frame)
            _frameEvents.emit(TransportFrameEvent(frame = frame, isReplay = isReplay))
        }
    }

    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult {
        val conversationKey = requireConversationKey(action.conversationId.orEmpty())
        if (conversationKey == null) {
            Log.w(
                TAG,
                "user_action missing conversation_id; refusing to route surfaceId=${action.surfaceId} event=${action.name}",
            )
            return A2uiActionDispatchResult.Failed
        }
        val socket = connection.getSocket()
        val stateNow = state.value
        val frame = action.toUserActionFrame().withActiveRoutingFallback(conversationKey)
        if (frame.runId == null) {
            Log.w(
                TAG,
                "user_action missing run_id; queueing until active run is known " +
                    "surfaceId=${action.surfaceId} event=${action.name} frameId=${frame.id}",
            )
            return enqueueA2uiAction(conversationKey, frame).also { result ->
                if (stateNow !is ChannelTransportState.Connected || socket == null) {
                    requestReconnectIfQueued(result, "missing run_id and no live socket")
                }
            }
        }
        if (stateNow is ChannelTransportState.Connected && socket != null) {
            val ok = connection.sendFrame(frame)
            if (ok) {
                Log.i(
                    TAG,
                    "user_action sent surfaceId=${action.surfaceId} event=${action.name} frameId=${frame.id}",
                )
                Log.d(TAG, "user_action context payload=${json.encodeToString(JsonObject.serializer(), frame.context)}")
                return A2uiActionDispatchResult.Sent(frame.id)
            }
            Log.w(
                TAG,
                "user_action sendFrame returned false; queueing surfaceId=${action.surfaceId} event=${action.name}",
            )
            return enqueueA2uiAction(conversationKey, frame).also { requestReconnectIfQueued(it, "sendFrame returned false") }
        }
        Log.w(
            TAG,
            "user_action no live socket (state=${stateNow::class.simpleName} socketNull=${socket == null}); " +
                "queueing surfaceId=${action.surfaceId} event=${action.name}",
        )
        return enqueueA2uiAction(conversationKey, frame).also { requestReconnectIfQueued(it, "no live socket") }
    }

    private fun enqueueA2uiAction(conversationId: String, frame: UserActionFrame): A2uiActionDispatchResult =
        synchronized(pendingA2uiActionLock) {
            val pending = conversationStateManager.pendingA2uiActions(conversationId)
            if (pending.size >= MAX_PENDING_A2UI_ACTIONS) {
                A2uiActionDispatchResult.Failed
            } else {
                pending.addLast(frame)
                A2uiActionDispatchResult.Queued(frame.id)
            }
        }

    private fun requeueA2uiActionFirst(conversationId: String, frame: UserActionFrame) {
        synchronized(pendingA2uiActionLock) {
            conversationStateManager.pendingA2uiActions(conversationId).addFirst(frame)
        }
    }

    private fun clearPendingA2uiActions(reason: String) {
        val dropped = synchronized(pendingA2uiActionLock) {
            val size = conversationStateManager.pendingA2uiActionCount()
            conversationStateManager.clearPendingA2uiActions()
            size
        }
        if (dropped > 0) {
            Log.i(TAG, "dropped $dropped queued user_action frame(s): $reason")
        }
    }

    private fun clearPendingA2uiActions(conversationId: String, reason: String) {
        val dropped = synchronized(pendingA2uiActionLock) {
            conversationStateManager.clearPendingA2uiActions(conversationId)
        }
        if (dropped > 0) {
            Log.i(TAG, "dropped $dropped queued user_action frame(s) for conversation=$conversationId: $reason")
        }
    }

    private fun requestReconnectIfQueued(result: A2uiActionDispatchResult, reason: String) {
        if (result !is A2uiActionDispatchResult.Queued) return
        requestReconnect(reason)
    }

    private fun requestReconnect(reason: String) {
        connection.requestReconnect(
            reason = reason,
            isConnecting = { state.value is ChannelTransportState.Connecting },
            connectFn = ::connect,
            onAttemptScheduled = { attempt, _ ->
                val disconnected = state.value as? ChannelTransportState.Disconnected
                _state.value = ChannelTransportState.Connecting(reconnecting = true, attempt = attempt)
                if (disconnected != null) {
                    _state.value = disconnected.copy(willReconnect = true, reconnectAttempt = attempt)
                }
            }
        )
    }

    private fun shouldReconnectOnClose(code: Int, wasClientInitiatedClose: Boolean): Boolean {
        if (wasClientInitiatedClose) return false
        if (code == NORMAL_CLOSE) return false
        if (code in AUTH_FAILURE_CLOSE_CODES) return false
        return true
    }

    private fun drainPendingA2uiActions(conversationId: String) {
        while (true) {
            val queuedFrame = synchronized(pendingA2uiActionLock) {
                val pending = conversationStateManager.existingPendingA2uiActions(conversationId)
                if (pending == null || pending.isEmpty()) null else pending.removeFirst()
            } ?: return
            val frame = queuedFrame.withActiveRoutingFallback(conversationId)
            if (frame.runId == null) {
                requeueA2uiActionFirst(conversationId, frame)
                return
            }
            val socket = connection.getSocket()
            if (state.value !is ChannelTransportState.Connected || socket == null || !connection.sendFrame(frame)) {
                requeueA2uiActionFirst(conversationId, frame)
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
            conversationId = conversationId,
            runId = runId,
            turnId = turnId,
            actionId = actionId,
        )

    private fun UserActionFrame.withActiveRoutingFallback(conversationId: String): UserActionFrame = copy(
        conversationId = this.conversationId ?: conversationId,
        runId = runId ?: conversationStateManager.currentRunId(conversationId),
        turnId = turnId ?: conversationStateManager.currentTurnId(conversationId),
    )

    companion object {
        private const val TAG = "ChannelTransport"
        private const val NORMAL_CLOSE = 1000
        private const val CURSOR_EXPIRED_ERROR_CODE = "cursor_expired"
        private const val MAX_PENDING_A2UI_ACTIONS = 16
        private const val NEW_CONVERSATION_STATE_KEY = "__new_conversation__"
        const val KEEPALIVE_PONG_TIMEOUT_CLOSE_CODE = 4001
        private val AUTH_FAILURE_CLOSE_CODES = setOf(4003, 4401, 4403)

        const val DEFAULT_CRON_TIMEOUT_MS: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS

        internal fun nowIso(): String = Instant.now().toString()

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

        private fun ServerFrame.conversationIdOrNull(): String? = when (this) {
            is ServerFrame.TurnStarted -> conversationId
            is ServerFrame.AssistantMessage -> conversationId
            is ServerFrame.ReasoningMessage -> conversationId
            is ServerFrame.ToolCallMessage -> conversationId
            is ServerFrame.ToolReturnMessage -> conversationId
            is ServerFrame.A2ui -> conversationId
            is ServerFrame.UserActionOutcome -> conversationId
            else -> null
        }

        private fun ServerFrame.seqOrNull(): Long? = when (this) {
            is ServerFrame.TurnStarted -> seq
            is ServerFrame.AssistantMessage -> seq ?: seqId?.toLong()
            is ServerFrame.ReasoningMessage -> seq ?: seqId?.toLong()
            is ServerFrame.ToolCallMessage -> seq
            is ServerFrame.ToolReturnMessage -> seq
            is ServerFrame.A2ui -> seq
            is ServerFrame.StopReason -> seq
            is ServerFrame.UsageStatistics -> seq
            is ServerFrame.TurnDone -> seq
            else -> null
        }

        private fun ServerFrame.cronRequestIdOrNull(): String? = when (this) {
            is ServerFrame.CronListResponse -> requestId
            is ServerFrame.CronAddResponse -> requestId
            is ServerFrame.CronGetResponse -> requestId
            is ServerFrame.CronDeleteResponse -> requestId
            is ServerFrame.CronDeleteAllResponse -> requestId
            is ServerFrame.SubagentListResponse -> requestId
            is ServerFrame.SubagentTodosResponse -> requestId
            else -> null
        }

        private fun JsonObject.withProtocolTypeAlias(): JsonObject {
            if (containsKey("type")) return this
            val messageType = this["message_type"]?.jsonPrimitive?.contentOrNull ?: return this
            return buildJsonObject {
                this@withProtocolTypeAlias.forEach { (key, value) -> put(key, value) }
                put("type", JsonPrimitive(messageType))
            }
        }
    }
}
