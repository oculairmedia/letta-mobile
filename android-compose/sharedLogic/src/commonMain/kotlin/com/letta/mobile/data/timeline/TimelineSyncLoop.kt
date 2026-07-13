package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolReturnMessage
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single sync loop per conversation. Acts as a thin orchestrator (under 200 lines).
 */
class TimelineSyncLoop(
    private val messageApi: TimelineTransport,
    private val conversationId: String,
    scope: CoroutineScope,
    logTag: String = "TimelineSync",
    ingestedListener: IngestedMessageListener? = null,
    ingestedListenerProvider: (() -> IngestedMessageListener?)? = null,
    pendingLocalStore: PendingLocalStore = NoOpPendingLocalStore,
    private val conversationCursorStore: ConversationCursorStore = NoOpConversationCursorStore,
    private val streamSilenceTimeoutMs: Long = STREAM_SILENCE_TIMEOUT_MS,
    private val startStreamSubscriber: Boolean = true,
) {
    private val loopJob = SupervisorJob(scope.coroutineContext[Job])
    private val loopScope = CoroutineScope(scope.coroutineContext + loopJob)

    private val _state = MutableStateFlow(Timeline(conversationId))
    val state: StateFlow<Timeline> = _state.asStateFlow()

    private val _streamSubscriberActive = MutableStateFlow(false)
    val streamSubscriberActive: StateFlow<Boolean> = _streamSubscriberActive.asStateFlow()

    private val writeMutex = Mutex()
    internal val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<TimelineSyncEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<TimelineSyncEvent> = _events.asSharedFlow()

    private val pendingToolReturnsByCallId = LinkedHashMap<String, ToolReturnMessage>()
    private val seenStreamMessageLock = SynchronizedObject()
    private val seenStreamMessageKeys = ArrayDeque<String>()
    private val seenStreamMessageKeySet = mutableSetOf<String>()
    private val holderFramesIn = MutableSharedFlow<LettaMessage>(extraBufferCapacity = 64)
    private val holderHydrationSeed = MutableStateFlow(Timeline(conversationId))
    
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

    private val hydrator = TimelineHydrator(
        conversationId = conversationId,
        messageApi = messageApi,
        pendingLocalStore = pendingLocalStore,
        conversationCursorStore = conversationCursorStore,
        writeMutex = writeMutex,
        state = _state,
        events = _events,
        holderHydrationSeed = holderHydrationSeed
    )

    private val outboundSendProcessor = TimelineOutboundSendProcessor(
        conversationId = conversationId,
        messageApi = messageApi,
        eventQueue = eventQueue,
        writeMutex = writeMutex,
        state = _state,
        events = _events,
        pendingLocalStore = pendingLocalStore,
        logTag = logTag,
        scope = loopScope,
        ingestStreamEvent = ::ingestStreamEvent,
        // letta-mobile-r3i1z: every ingestStreamEvent above marks the ws/external-
        // transport latch active (correct WHILE our own turn streams through the
        // send flow — the persistent subscriber sees the same frames and must not
        // double-ingest). Clear it when the send stream ends, or the subscriber
        // stays muted forever and fanned-out turns from OTHER clients (Iroh
        // observer frames) are dropped at submitStreamEvent as skippedDualIngest:
        // the desktop rendered the prompt (via the external-run reconcile) but
        // never the reply. ChatSendCoordinator clears the same latch at turn end
        // on the WS/iroh coordinator path; this is the loop-owned send's mirror.
        onSendStreamEnded = { wsSubscription.clear() },
    )

    private val stateTransitionHandler = TimelineStateTransitionHandler(
        conversationId = conversationId,
        state = _state,
        events = _events,
        sendQueue = outboundSendProcessor.sendQueue,
        writeMutex = writeMutex
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
        if (startStreamSubscriber) {
            loopScope.launch { runStreamSubscriber() }
        }
    }

    suspend fun emitHydrateFailed(message: String) {
        _events.emit(TimelineSyncEvent.HydrateFailed(message))
    }

    fun close() {
        eventQueue.close(CancellationException("TimelineSyncLoop closed"))
        outboundSendProcessor.sendQueue.close(CancellationException("TimelineSyncLoop closed"))
        loopJob.cancel(CancellationException("TimelineSyncLoop closed"))
    }

    @Volatile
    var hasHydratedSuccessfully: Boolean = false
        private set

    suspend fun hydrate(limit: Int = 50, recordConversationCursor: Boolean = false, fallbackCursorSeq: Long? = null) {
        hydrator.hydrate(limit, recordConversationCursor, fallbackCursorSeq)
        hasHydratedSuccessfully = true
    }

    suspend fun send(content: String, attachments: List<MessageContentPart.Image> = emptyList()): String {
        return outboundSendProcessor.send(content, attachments)
    }

    suspend fun appendExternalTransportLocal(content: String, otid: String, attachments: List<MessageContentPart.Image> = emptyList()): String {
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
                        if (shouldDropDuplicateStreamMessage(event.message, event.source)) {
                            event.ack?.complete(Unit)
                        } else {
                            streamDispatcher.dispatch(event.message, event.source)
                            event.ack?.complete(Unit)
                        }
                    }
                    is TimelineGatewayEvent.LocalSendAppend -> stateTransitionHandler.applyLocalSendAppend(event)
                    is TimelineGatewayEvent.ExternalTransportLocalAppend -> externalTransportAppender.applyExternalTransportLocalAppend(event)
                    is TimelineGatewayEvent.ReconcileAfterSendSnapshot -> applyReconcileAfterSendSnapshot(event)
                    is TimelineGatewayEvent.RecentMessagesSnapshot -> recentMessagesReconciler.applyRecentMessagesSnapshot(event)
                    is TimelineGatewayEvent.PostHandlerCollapse -> event.ack.complete(Unit)
                    is TimelineGatewayEvent.RetrySend -> stateTransitionHandler.applyRetrySend(event)
                    is TimelineGatewayEvent.MarkSent -> stateTransitionHandler.applyMarkSent(event)
                    is TimelineGatewayEvent.MarkFailed -> stateTransitionHandler.applyMarkFailed(event)
                    is TimelineGatewayEvent.CleanupAbandonedAssistantFragments -> applyCleanupAbandonedAssistantFragments(event)
                }
            } catch (cancelled: CancellationException) {
                completeGatewayEventExceptionally(event, cancelled)
                throw cancelled
            } catch (t: Throwable) {
                completeGatewayEventExceptionally(event, t)
                Telemetry.error("TimelineSync", "gateway.eventFailed", t, "conversationId" to conversationId, "event" to event::class.simpleName)
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
            is TimelineGatewayEvent.CleanupAbandonedAssistantFragments -> event.ack.completeExceptionally(t)
        }
    }

    private suspend fun applyReconcileAfterSendSnapshot(event: TimelineGatewayEvent.ReconcileAfterSendSnapshot) {
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

    private suspend fun submitReconcileAfterSendSnapshot(otid: String, serverMessages: List<LettaMessage>): ReconcileAfterSendResult {
        val ack = CompletableDeferred<ReconcileAfterSendResult>()
        eventQueue.send(TimelineGatewayEvent.ReconcileAfterSendSnapshot(otid = otid, serverMessages = serverMessages, ack = ack))
        return ack.await()
    }

    suspend fun cleanupAbandonedAssistantFragments(
        runId: String?,
        turnId: String?,
        reason: String,
        candidateRunIds: Set<String> = emptySet(),
    ): Int {
        val ack = CompletableDeferred<Int>()
        eventQueue.send(TimelineGatewayEvent.CleanupAbandonedAssistantFragments(runId, turnId, reason, candidateRunIds, ack))
        return ack.await()
    }

    private suspend fun applyCleanupAbandonedAssistantFragments(event: TimelineGatewayEvent.CleanupAbandonedAssistantFragments) {
        var removed = 0
        writeMutex.withLock {
            val result = _state.value.cleanupAbandonedAssistantFragments(
                runId = event.runId,
                turnId = event.turnId,
                reason = event.reason,
                candidateRunIds = event.candidateRunIds,
            )
            removed = result.suppressions.size
            _state.value = result.timeline
        }
        if (removed > 0 && !event.runId.isNullOrBlank()) {
            _events.emit(TimelineSyncEvent.OrphanAssistantFragmentsCleaned(event.runId, event.turnId, removed, event.reason))
        }
        event.ack.complete(removed)
    }

    /**
     * letta-mobile-fe51r (P2b pointer diet): fetch the full body of a
     * tool-return message that `message.list` projected to a preview, then
     * fold it into the owning TOOL_CALL event (clearing the truncation
     * marker). Returns true when a full body was fetched and applied.
     */
    suspend fun resolveTruncatedToolReturn(messageId: String): Boolean {
        val message = runCatching { messageApi.getToolReturn(conversationId, messageId) }
            .onFailure { t ->
                Telemetry.error(
                    "TimelineSync", "toolReturn.resolveFailed", t,
                    "conversationId" to conversationId,
                    "messageId" to messageId,
                )
            }
            .getOrNull() as? ToolReturnMessage ?: return false
        if (message.toolReturnTruncated == true) return false
        writeMutex.withLock {
            applyReturnsAndResponsesFromSnapshot(listOf(message), _state)
        }
        Telemetry.event(
            "TimelineSync", "toolReturn.resolved",
            "conversationId" to conversationId,
            "messageId" to messageId,
            "bodyLen" to (message.toolReturn.funcResponse?.length ?: 0),
        )
        return true
    }

    suspend fun reconcileForExternalRun(runId: String) {
        reconcileForExternalRun(runId) { name, attrs, allowWhileActive ->
            recentMessagesReconciler.reconcileRecentMessagesFromServer(name, attrs, allowWhileActive)
        }
    }

    suspend fun reconcileRecentMessages(reason: String, forceRefresh: Boolean = false): Int {
        return recentMessagesReconciler.reconcileRecentMessages(reason, forceRefresh)
    }

    suspend fun markExternalTransportLocalSent(otid: String) {
        externalTransportAppender.markExternalTransportLocalSent(otid)
    }

    suspend fun markExternalTransportLocalFailed(otid: String) {
        externalTransportAppender.markExternalTransportLocalFailed(otid)
    }

    suspend fun reconcileExternalTransportSend(agentId: String, externalConversationId: String, otid: String) {
        externalTransportAppender.reconcileExternalTransportSend(agentId, externalConversationId, otid)
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
        Telemetry.event("TimelineSync", "streamSubscriber.activeChanged", "conversationId" to conversationId, "active" to active)
    }

    suspend fun submitStreamEvent(message: LettaMessage) {
        if (wsSubscription.isActive()) {
            Telemetry.event("TimelineSync", "streamSubscriber.skippedDualIngest", "conversationId" to conversationId, "messageType" to message.messageType, "messageId" to message.id)
            return
        }
        eventQueue.send(TimelineGatewayEvent.StreamMessage(message, source = "subscriber.loop${hashCode()}"))
    }

    suspend fun ingestStreamEvent(message: LettaMessage, source: String = "external") {
        wsSubscription.markActive()
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.StreamMessage(message, ack, source = "$source.loop${hashCode()}"))
        ack.await()
    }

    private fun shouldDropDuplicateStreamMessage(message: LettaMessage, source: String): Boolean {
        val key = streamMessageKey(message) ?: return false
        val duplicate = synchronized(seenStreamMessageLock) {
            if (key in seenStreamMessageKeySet) {
                true
            } else {
                seenStreamMessageKeySet.add(key)
                seenStreamMessageKeys.addLast(key)
                while (seenStreamMessageKeys.size > MAX_SEEN_STREAM_MESSAGES) {
                    seenStreamMessageKeySet.remove(seenStreamMessageKeys.removeFirst())
                }
                false
            }
        }
        if (duplicate) {
            Telemetry.event(
                "TimelineSync", "streamMessage.exactDuplicateDropped",
                "conversationId" to conversationId,
                "messageId" to message.id,
                "messageType" to message.messageType,
                "seqId" to (message.seqId ?: -1),
                "source" to source,
            )
        }
        return duplicate
    }

    private fun streamMessageKey(message: LettaMessage): String? {
        // Only deduplicate frames with explicit sequence identity (seqId).
        // Forward incremental streaming deltas (no seqId) may legitimately
        // have identical content when streaming character-by-character and
        // must NOT be deduplicated based on content alone.
        val seqId = message.seqId
        if (seqId != null && seqId >= 0) {
            return "seq|$seqId|${message.messageType}|${message.id}"
        }
        // No seqId: this is a forward streaming delta. Do not deduplicate.
        return null
    }

    fun clearExternalTransportActive() {
        wsSubscription.clear()
    }

    companion object {
        private const val STREAM_HEARTBEAT_EXPECTED_MS = 30_000L
        private const val STREAM_SILENCE_TIMEOUT_MS = STREAM_HEARTBEAT_EXPECTED_MS * 12
        private const val MAX_SEEN_STREAM_MESSAGES = 512
        private val activeStreamCount = TimelineAtomicCounter(0)
        internal val DEFAULT_INCLUDE_TYPES = listOf("assistant_message", "reasoning_message", "tool_call_message", "tool_return_message")
    }
}
