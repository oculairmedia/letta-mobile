package com.letta.mobile.data.timeline

import android.util.Log
import com.letta.mobile.core.BuildConfig
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.util.Telemetry
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.stream.SseParser
import java.time.Instant
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Single sync loop per conversation.
 *
 * This class acts as a thin orchestrator. It consumes events from [TimelineGatewayEvent]
 * and delegates them to dedicated, typed handlers in sibling files.
 */
class TimelineSyncLoop(
    private val messageApi: MessageApi,
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val logTag: String = "TimelineSync",
    private val ingestedListener: IngestedMessageListener? = null,
    private val ingestedListenerProvider: (() -> IngestedMessageListener?)? = null,
    private val pendingLocalStore: PendingLocalStore = NoOpPendingLocalStore,
    private val conversationCursorStore: ConversationCursorStore = NoOpConversationCursorStore,
    private val streamSilenceTimeoutMs: Long = STREAM_SILENCE_TIMEOUT_MS,
) {
    private val loopJob = SupervisorJob(scope.coroutineContext[Job])
    private val loopScope = CoroutineScope(scope.coroutineContext + loopJob)

    private val previewJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val _state = MutableStateFlow(Timeline(conversationId))
    val state: StateFlow<Timeline> = _state.asStateFlow()

    private val _streamSubscriberActive = MutableStateFlow(false)
    internal val streamSubscriberActive: StateFlow<Boolean> = _streamSubscriberActive.asStateFlow()

    // Serialize all mutations so append/replace logic is safe under concurrency.
    private val writeMutex = Mutex()

    // Queue of incoming timeline events.
    internal val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)

    // Queue of outgoing sends.
    private val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<TimelineSyncEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<TimelineSyncEvent> = _events.asSharedFlow()

    private val pendingToolReturnsByCallId = LinkedHashMap<String, ToolReturnMessage>()

    private val holderFramesIn = MutableSharedFlow<LettaMessage>(extraBufferCapacity = 64)
    private val holderHydrationSeed = MutableStateFlow(Timeline(conversationId))
    
    @Suppress("UnusedPrivateMember")
    private val holder = com.letta.mobile.data.timeline.experimental.ConversationStateHolder(
        conversationId = conversationId,
        scope = loopScope,
        frames = holderFramesIn.asSharedFlow(),
        hydrationSeed = holderHydrationSeed,
    )

    private val ingestNotificationDispatcher = TimelineIngestNotificationDispatcher(
        conversationId = conversationId,
        listener = ingestedListener,
        listenerProvider = ingestedListenerProvider,
    )

    // Decomposed typed handlers
    private val wsSubscription = TimelineWsSubscription(conversationId)

    private val streamDispatcher = TimelineStreamDispatcher(
        conversationId = conversationId,
        writeMutex = writeMutex,
        state = _state,
        events = _events,
        pendingToolReturnsByCallId = pendingToolReturnsByCallId,
        conversationCursorStore = conversationCursorStore,
        loopScope = loopScope,
        ingestNotificationDispatcher = ingestNotificationDispatcher,
        holderFramesIn = holderFramesIn,
        getHolderEventCount = { holder.state.value.events.size }
    )

    private val recentMessagesReconciler = TimelineRecentMessagesReconciler(
        conversationId = conversationId,
        messageApi = messageApi,
        eventQueue = eventQueue,
        state = _state,
        streamSubscriberActive = _streamSubscriberActive.asStateFlow(),
        writeMutex = writeMutex,
        applyReturnsAndResponsesFromSnapshot = { snapshot -> applyReturnsAndResponsesFromSnapshot(snapshot, _state) }
    )

    private val externalTransportAppender = TimelineExternalTransportAppender(
        conversationId = conversationId,
        messageApi = messageApi,
        eventQueue = eventQueue,
        state = _state,
        events = _events,
        writeMutex = writeMutex,
        pendingLocalStore = pendingLocalStore,
        submitReconcileAfterSendSnapshot = ::submitReconcileAfterSendSnapshot
    )

    init {
        loopScope.launch { processEventQueue() }
        loopScope.launch { processSendQueue() }
        loopScope.launch { runStreamSubscriber() }
    }

    internal suspend fun emitHydrateFailed(message: String) {
        _events.emit(TimelineSyncEvent.HydrateFailed(message))
    }

    fun close() {
        eventQueue.close(CancellationException("TimelineSyncLoop closed"))
        sendQueue.close(CancellationException("TimelineSyncLoop closed"))
        loopJob.cancel(CancellationException("TimelineSyncLoop closed"))
    }

    internal sealed interface TimelineGatewayEvent {
        data class StreamMessage(
            val message: LettaMessage,
            val ack: CompletableDeferred<Unit>? = null,
        ) : TimelineGatewayEvent
        data class LocalSendAppend(
            val pending: PendingSend,
            val sentAt: Instant,
            val ack: CompletableDeferred<Unit>,
        ) : TimelineGatewayEvent

        data class ReconcileAfterSendSnapshot(
            val otid: String,
            val serverMessages: List<LettaMessage>,
            val ack: CompletableDeferred<ReconcileAfterSendResult>,
        ) : TimelineGatewayEvent

        data class RecentMessagesSnapshot(
            val serverMessages: List<LettaMessage>,
            val telemetryName: String,
            val telemetryAttrs: List<Pair<String, Any?>>,
            val ack: CompletableDeferred<Int>,
        ) : TimelineGatewayEvent

        data class ExternalTransportLocalAppend(
            val content: String,
            val otid: String,
            val attachments: List<MessageContentPart.Image>,
            val sentAt: Instant,
            val ack: CompletableDeferred<String>,
        ) : TimelineGatewayEvent

        data class PostHandlerCollapse(val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
        data class RetrySend(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
        data class MarkSent(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
        data class MarkFailed(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
    }

    internal data class PendingSend(
        val otid: String,
        val content: String,
        val attachments: List<MessageContentPart.Image> = emptyList(),
    )

    suspend fun hydrate(
        limit: Int = 50,
        recordConversationCursor: Boolean = false,
        fallbackCursorSeq: Long? = null,
    ) {
        val timer = Telemetry.startTimer("TimelineSync", "hydrate")
        val timelineBeforeFetch = writeMutex.withLock { _state.value }
        try {
            val rawFetchLimit = hydrateRawFetchLimit(limit)
            val response = normalizeHydratedMessageOrder(
                messageApi.listConversationMessages(
                    conversationId = ConversationId(conversationId),
                    limit = rawFetchLimit,
                    order = "desc",
                ).reversed()
            )
            val diskRecords = runCatching { pendingLocalStore.load(conversationId) }
                .getOrDefault(emptyList())
            val hydrateEndSeq = if (recordConversationCursor) {
                response.mapNotNull { it.seqId?.toLong() }
                    .plus(listOfNotNull(fallbackCursorSeq))
                    .maxOrNull()
            } else {
                null
            }

            val hydrated = writeMutex.withLock {
                TimelineHydrationReducer.reduce(
                    conversationId = conversationId,
                    serverMessagesChronological = response,
                    timelineBeforeFetch = timelineBeforeFetch,
                    currentTimeline = _state.value,
                    diskRecords = diskRecords,
                ).also { result ->
                    _state.value = result.timeline
                    holderHydrationSeed.value = result.timeline
                }
            }
            if (recordConversationCursor && hydrateEndSeq != null) {
                conversationCursorStore.recordFrame(conversationId, hydrateEndSeq)
                Telemetry.event(
                    "TimelineSync", "hydrate.cursorRepaired",
                    "conversationId" to conversationId,
                    "cursorSeq" to hydrateEndSeq,
                )
            }
            _events.emit(TimelineSyncEvent.Hydrated(hydrated.visibleEventCount))
            timer.stop(
                "conversationId" to conversationId,
                "rawCount" to response.size,
                "eventCount" to hydrated.visibleEventCount,
                "cursorSeq" to (hydrateEndSeq ?: -1L),
            )
            dumpTimelineState("hydrate", conversationId, _state.value)
        } catch (t: Throwable) {
            timer.stopError(t, "conversationId" to conversationId)
            throw t
        }
    }

    suspend fun send(
        content: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val otid = newOtid()
        val sentAt = Instant.now()
        val pending = PendingSend(otid, content, attachments)
        val appendAck = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.LocalSendAppend(pending, sentAt, appendAck))
        appendAck.await()
        if (attachments.isNotEmpty()) {
            runCatching {
                pendingLocalStore.save(
                    PendingLocalRecord(
                        otid = otid,
                        conversationId = conversationId,
                        content = content,
                        attachments = attachments,
                        sentAt = sentAt,
                    )
                )
            }.onFailure { t ->
                Telemetry.error(logTag, "send.persistFailed", t, "otid" to otid)
            }
        }
        return otid
    }

    internal suspend fun appendExternalTransportLocal(
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        return externalTransportAppender.appendExternalTransportLocal(content, otid, attachments)
    }

    suspend fun postHandlerCollapse() {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.PostHandlerCollapse(ack))
        ack.await()
    }

    suspend fun retry(otid: String) {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.RetrySend(otid, ack))
        ack.await()
    }

    private suspend fun processEventQueue() {
        for (event in eventQueue) {
            try {
                when (event) {
                    is TimelineGatewayEvent.StreamMessage -> {
                        streamDispatcher.dispatch(event.message)
                        event.ack?.complete(Unit)
                    }
                    is TimelineGatewayEvent.LocalSendAppend -> applyLocalSendAppend(event)
                    is TimelineGatewayEvent.ExternalTransportLocalAppend -> externalTransportAppender.applyExternalTransportLocalAppend(event)
                    is TimelineGatewayEvent.ReconcileAfterSendSnapshot -> applyReconcileAfterSendSnapshot(event)
                    is TimelineGatewayEvent.RecentMessagesSnapshot -> recentMessagesReconciler.applyRecentMessagesSnapshot(event)
                    is TimelineGatewayEvent.PostHandlerCollapse -> event.ack.complete(Unit)
                    is TimelineGatewayEvent.RetrySend -> applyRetrySend(event)
                    is TimelineGatewayEvent.MarkSent -> applyMarkSent(event)
                    is TimelineGatewayEvent.MarkFailed -> applyMarkFailed(event)
                }
            } catch (cancelled: CancellationException) {
                completeGatewayEventExceptionally(event, cancelled)
                throw cancelled
            } catch (t: Throwable) {
                completeGatewayEventExceptionally(event, t)
                Telemetry.error(
                    "TimelineSync", "gateway.eventFailed", t,
                    "conversationId" to conversationId,
                    "event" to event::class.simpleName,
                )
            }
        }
    }

    private fun completeGatewayEventExceptionally(event: TimelineGatewayEvent, t: Throwable) {
        when (event) {
            is TimelineGatewayEvent.StreamMessage -> event.ack?.completeExceptionally(t)
            is TimelineGatewayEvent.LocalSendAppend -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.ExternalTransportLocalAppend -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.ReconcileAfterSendSnapshot -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.RecentMessagesSnapshot -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.PostHandlerCollapse -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.RetrySend -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.MarkSent -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.MarkFailed -> event.ack.completeExceptionally(t)
        }
    }

    private suspend fun applyLocalSendAppend(event: TimelineGatewayEvent.LocalSendAppend) {
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = _state.value.nextLocalPosition(),
                otid = event.pending.otid,
                content = event.pending.content,
                role = Role.USER,
                sentAt = event.sentAt,
                deliveryState = DeliveryState.SENDING,
                attachments = event.pending.attachments,
            )
            _state.value = _state.value.append(local)
            sendQueue.send(event.pending)
        }
        _events.emit(TimelineSyncEvent.LocalAppended(event.pending.otid))
        Telemetry.event(
            "TimelineSync", "send.localAppended",
            "otid" to event.pending.otid,
            "conversationId" to conversationId,
            "contentLength" to event.pending.content.length,
        )
        event.ack.complete(Unit)
    }

    private suspend fun applyReconcileAfterSendSnapshot(
        event: TimelineGatewayEvent.ReconcileAfterSendSnapshot,
    ) {
        val result = applyReconcileAfterSendSnapshot(
            otid = event.otid,
            conversationId = conversationId,
            serverMessages = event.serverMessages,
            writeMutex = writeMutex,
            state = _state,
        )
        writeMutex.withLock {
            applyReturnsAndResponsesFromSnapshot(event.serverMessages, _state)
        }
        event.ack.complete(result)
    }

    private suspend fun applyRetrySend(event: TimelineGatewayEvent.RetrySend) {
        writeMutex.withLock {
            val existing = _state.value.findByOtid(event.otid)
            if (existing is TimelineEvent.Local && existing.deliveryState == DeliveryState.FAILED) {
                _state.value = _state.value.copy(events = _state.value.events.map {
                    if (it.otid == event.otid && it is TimelineEvent.Local) {
                        it.copy(deliveryState = DeliveryState.SENDING)
                    } else it
                })
                sendQueue.send(PendingSend(event.otid, existing.content, existing.attachments))
            }
        }
        event.ack.complete(Unit)
    }

    private suspend fun applyMarkSent(event: TimelineGatewayEvent.MarkSent) {
        writeMutex.withLock {
            _state.value = _state.value.markSent(event.otid)
        }
        event.ack.complete(Unit)
    }

    private suspend fun applyMarkFailed(event: TimelineGatewayEvent.MarkFailed) {
        writeMutex.withLock {
            _state.value = _state.value.markFailed(event.otid)
        }
        event.ack.complete(Unit)
    }

    private suspend fun processSendQueue() {
        for (pending in sendQueue) {
            val roundtrip = Telemetry.startTimer("TimelineSync", "send.roundtrip")
            Telemetry.event(
                "TimelineSync", "send.dequeued",
                "otid" to pending.otid,
                "conversationId" to conversationId,
            )
            try {
                streamAndReconcile(pending.content, pending.otid, pending.attachments)
                roundtrip.stop("otid" to pending.otid)
            } catch (t: Throwable) {
                Telemetry.error(
                    "TimelineSync", "send.failed", t,
                    "otid" to pending.otid,
                    "conversationId" to conversationId,
                )
                val ack = CompletableDeferred<Unit>()
                eventQueue.send(TimelineGatewayEvent.MarkFailed(pending.otid, ack))
                ack.await()
                _events.emit(TimelineSyncEvent.StreamError("send", t.message ?: "unknown"))
            }
        }
    }

    private suspend fun streamAndReconcile(
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ) {
        val contentElement: kotlinx.serialization.json.JsonElement = if (attachments.isEmpty()) {
            JsonPrimitive(content)
        } else {
            buildContentParts(content, attachments).toJsonArray()
        }
        val request = MessageCreateRequest(
            messages = listOf(
                kotlinx.serialization.json.Json.encodeToJsonElement(
                    MessageCreate.serializer(),
                    MessageCreate(
                        role = "user",
                        content = contentElement,
                        otid = otid,
                    )
                )
            ),
            streaming = true,
            includePings = true,
            includeReturnMessageTypes = DEFAULT_INCLUDE_TYPES,
        )
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "send.requestBody otid=$otid preview=${previewRequest(request, previewJson)}")
        }
        val postTimer = Telemetry.startTimer("TimelineSync", "send.post")
        val channel = messageApi.sendConversationMessage(ConversationId(conversationId), request)
        postTimer.stop("otid" to otid)

        val streamTimer = Telemetry.startTimer("TimelineSync", "send.stream")
        var eventCount = 0
        var firstEventLogged = false
        val firstEventTimer = Telemetry.startTimer("TimelineSync", "send.firstEvent")

        SseParser.parse(channel).collect { message ->
            eventCount++
            if (!firstEventLogged) {
                firstEventLogged = true
                firstEventTimer.stop("otid" to otid)
            }
            ingestStreamEvent(message)
            _events.emit(TimelineSyncEvent.ServerEvent(message))
        }

        streamTimer.stop("otid" to otid, "eventCount" to eventCount)

        val markSentAck = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkSent(otid, markSentAck))
        markSentAck.await()

        reconcileAfterSend(otid)
    }

    private suspend fun reconcileAfterSend(otid: String) {
        reconcileAfterSend(
            otid = otid,
            conversationId = conversationId,
            writeMutex = writeMutex,
            state = _state,
            events = _events,
            pendingLocalStore = pendingLocalStore,
            listMessagesWithRetry = ::listMessagesWithRetry
        )
    }

    private suspend fun submitReconcileAfterSendSnapshot(
        otid: String,
        serverMessages: List<LettaMessage>,
    ): ReconcileAfterSendResult {
        val ack = CompletableDeferred<ReconcileAfterSendResult>()
        eventQueue.send(
            TimelineGatewayEvent.ReconcileAfterSendSnapshot(
                otid = otid,
                serverMessages = serverMessages,
                ack = ack,
            )
        )
        return ack.await()
    }

    internal suspend fun reconcileForExternalRun(runId: String) {
        reconcileForExternalRun(runId) { name, attrs ->
            recentMessagesReconciler.reconcileRecentMessagesFromServer(name, attrs)
        }
    }

    internal suspend fun reconcileRecentMessages(
        reason: String,
        forceRefresh: Boolean = false,
    ) {
        recentMessagesReconciler.reconcileRecentMessages(reason, forceRefresh)
    }

    internal suspend fun markExternalTransportLocalSent(otid: String) {
        externalTransportAppender.markExternalTransportLocalSent(otid)
    }

    internal suspend fun markExternalTransportLocalFailed(otid: String) {
        externalTransportAppender.markExternalTransportLocalFailed(otid)
    }

    internal suspend fun reconcileExternalTransportSend(
        agentId: String,
        externalConversationId: String,
        otid: String,
    ) {
        externalTransportAppender.reconcileExternalTransportSend(agentId, externalConversationId, otid)
    }

    private suspend fun listMessagesWithRetry(otid: String): List<LettaMessage> {
        val afterCursor = _state.value.liveCursor
        var lastError: Throwable? = null
        for (attempt in 0 until RECONCILE_RETRY_ATTEMPTS) {
            try {
                return messageApi.listConversationMessages(
                    conversationId = ConversationId(conversationId),
                    limit = if (afterCursor != null) 50 else RECONCILE_LIMIT,
                    after = afterCursor,
                    order = if (afterCursor != null) "asc" else "desc",
                )
            } catch (t: Throwable) {
                if (!isRetryableReconcileError(t) || attempt == RECONCILE_RETRY_ATTEMPTS - 1) {
                    throw t
                }
                lastError = t
                Telemetry.error(
                    "TimelineSync", "reconcile.retry", t,
                    "otid" to otid,
                    "attempt" to attempt + 1,
                )
                delay(RECONCILE_RETRY_BACKOFF_MS shl attempt)
            }
        }
        throw lastError ?: IllegalStateException("listMessagesWithRetry exhausted without error")
    }

    private suspend fun runStreamSubscriber() {
        runStreamSubscriber(
            conversationId = conversationId,
            messageApi = messageApi,
            activeStreamCount = activeStreamCount,
            events = _events,
            seenRunIds = recentMessagesReconciler.seenRunIds,
            streamSilenceTimeoutMs = streamSilenceTimeoutMs,
            reconcileForExternalRun = ::reconcileForExternalRun,
            ingestStreamEvent = ::submitStreamEvent,
            setStreamActive = ::setStreamSubscriberActive,
        )
    }

    private suspend fun setStreamSubscriberActive(active: Boolean) {
        if (_streamSubscriberActive.value == active) return
        _streamSubscriberActive.value = active
        Telemetry.event(
            "TimelineSync", "streamSubscriber.activeChanged",
            "conversationId" to conversationId,
            "active" to active,
        )
    }

    internal suspend fun submitStreamEvent(message: LettaMessage) {
        if (wsSubscription.isActive()) {
            Telemetry.event(
                "TimelineSync", "streamSubscriber.skippedDualIngest",
                "conversationId" to conversationId,
                "messageType" to message.messageType,
                "messageId" to message.id,
            )
            return
        }
        eventQueue.send(TimelineGatewayEvent.StreamMessage(message))
    }

    internal suspend fun ingestStreamEvent(message: LettaMessage) {
        wsSubscription.markActive()
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.StreamMessage(message, ack))
        ack.await()
    }

    internal fun clearExternalTransportActive() {
        wsSubscription.clear()
    }

    companion object {
        private const val STREAM_HEARTBEAT_EXPECTED_MS = 30_000L
        private const val STREAM_SILENCE_TIMEOUT_MS = STREAM_HEARTBEAT_EXPECTED_MS * 3

        private val activeStreamCount = AtomicInteger(0)

        private const val RECONCILE_LIMIT = 250
        private const val RECONCILE_RETRY_ATTEMPTS = 3
        private const val RECONCILE_RETRY_BACKOFF_MS = 200L

        internal val DEFAULT_INCLUDE_TYPES = listOf(
            "assistant_message",
            "reasoning_message",
            "tool_call_message",
            "tool_return_message",
        )
    }
}
