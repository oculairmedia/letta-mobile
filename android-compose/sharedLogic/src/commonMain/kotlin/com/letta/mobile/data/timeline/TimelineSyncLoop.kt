package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.NoOpConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotMutationCharacterizer
import com.letta.mobile.data.timeline.snapshot.SnapshotStructuralSummary
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext

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
    startStreamSubscriber: Boolean = true,
    // letta-mobile-c4igq.4: owning agent for this conversation loop (from the
    // repository cache key). Threaded onto ingested Confirmed events for render
    // scoping. Null when unknown/legacy. LAST param so positional callers are
    // unaffected.
    private val agentId: String? = null,
    private val confirmedTimelineStore: ConfirmedTimelineStore = NoOpConfirmedTimelineStore,
    private val timelineScope: TimelineScope? = null,
    initialTimeline: Timeline? = null,
    initialRevision: Long = 0L,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = timelineIoDispatcher,
) {
    private val loopJob = SupervisorJob(scope.coroutineContext[Job])
    private val loopScope = object : CoroutineScope {
        override val coroutineContext = scope.coroutineContext + loopJob
    }

    private val _streamSubscriberActive = MutableStateFlow(false)
    val streamSubscriberActive: StateFlow<Boolean> = _streamSubscriberActive.asStateFlow()

    internal val eventQueue = Channel<TimelineGatewayEvent>(GATEWAY_EVENT_CAPACITY)
    private val _events = MutableSharedFlow<TimelineSyncEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<TimelineSyncEvent> = _events.asSharedFlow()

    private var snapshotRevision: Long = initialRevision
    private var lastPersistedFingerprint: Long? = null
    // The first successful write establishes this compact baseline; failed/stale writes do not advance it.
    private var lastPersistedSnapshot: SnapshotStructuralSummary? = null
    private val persistRequests = Channel<SnapshotPersistRequest>(Channel.CONFLATED)
    private val persistMutex = Mutex()
    private val persistJob: Job
    private val eventProcessorJob: Job
    private val streamSubscriberJob: Job?

    private val seenStreamMessageLock = SynchronizedObject()
    private val seenStreamMessageKeys = ArrayDeque<String>()
    private val seenStreamMessageKeySet = mutableSetOf<String>()

    private val ingestNotificationDispatcher = TimelineIngestNotificationDispatcher(
        conversationId = conversationId,
        listener = ingestedListener,
        listenerProvider = ingestedListenerProvider,
    )

    private val wsSubscription = TimelineWsSubscription(conversationId)

    private val streamDispatcher by lazy {
        TimelineStreamDispatcher(
            conversationId = conversationId,
            agentId = agentId,
            processor = timelineProcessor,
            onStreamFrameIngested = { scheduleSnapshotPersist(immediate = false) },
        )
    }

    private val recentMessagesReconciler by lazy {
        TimelineRecentMessagesReconciler(
        conversationId = conversationId,
        scope = loopScope,
        messageApi = messageApi,
        eventQueue = eventQueue,
        state = state,
        streamSubscriberActive = _streamSubscriberActive.asStateFlow(),
        processor = timelineProcessor,
            onSnapshotApplied = { scheduleSnapshotPersist(immediate = true) },
        )
    }

    private val hydrator by lazy {
        TimelineHydrator(
            conversationId = conversationId,
            messageApi = messageApi,
            pendingLocalStore = pendingLocalStore,
            events = _events,
            timelineProcessor = timelineProcessor,
            onHydrationCommitted = { scheduleSnapshotPersist(immediate = true) },
        )
    }

    internal fun scheduleSnapshotPersist(immediate: Boolean = false) {
        timelineScope ?: return
        if (confirmedTimelineStore === NoOpConfirmedTimelineStore) return
        persistRequests.trySend(
            if (immediate) SnapshotPersistRequest.Immediate else SnapshotPersistRequest.Debounced,
        )
    }

    private suspend fun runSnapshotPersistence() {
        for (request in persistRequests) {
            if (request == SnapshotPersistRequest.Debounced) {
                delay(SNAPSHOT_PERSIST_DEBOUNCE)
            }
            while (persistRequests.tryReceive().isSuccess) {
                // Coalesce all timeline changes received during the debounce window into the latest state.
            }
            flushSnapshotNow(prune = true)
        }
    }

    suspend fun flushSnapshotNow(prune: Boolean = false) {
        val snapshotScope = timelineScope ?: return
        if (confirmedTimelineStore === NoOpConfirmedTimelineStore) return
        persistMutex.withLock {
            persistCurrentSnapshot(snapshotScope, prune)
        }
    }

    private suspend fun persistCurrentSnapshot(snapshotScope: TimelineScope, prune: Boolean) {
        // Capture one immutable processor commit so content and sequence cannot race.
        val committedState = timelineProcessor.state.value
        val (provisionalEnvelope, fingerprint) = withContext(ioDispatcher) {
            val envelope = TimelineSnapshotCodec.timelineToStoredEnvelope(
                timeline = committedState.timeline,
                scope = snapshotScope,
                revision = snapshotRevision,
                writtenAtMillis = timelineCurrentTimeMillis(),
            )
            envelope to TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(envelope)
        }

        if (fingerprint == lastPersistedFingerprint) {
            Telemetry.event(
                "TimelineSync", "snapshotPersist.identicalSkipped",
                "conversationId" to conversationId,
                "revision" to snapshotRevision,
                "fingerprint" to fingerprint,
                "eventCount" to provisionalEnvelope.events.size,
            )
            return
        }

        val revision = ++snapshotRevision
        val envelope = provisionalEnvelope.copy(revision = revision)
        val startedAtMs = timelineCurrentTimeMillis()

        try {
            withContext(ioDispatcher + NonCancellable) {
                val written = confirmedTimelineStore.writeSnapshot(envelope)
                if (prune) {
                    confirmedTimelineStore.prune(snapshotScope.backendId, MAX_RETAINED_SNAPSHOTS)
                }
                val durationMs = timelineCurrentTimeMillis() - startedAtMs
                if (!written) {
                    Telemetry.event(
                        "TimelineSync", "snapshotPersist.staleRejected",
                        "conversationId" to conversationId,
                        "revision" to revision,
                        level = Telemetry.Level.WARN,
                    )
                } else {
                    lastPersistedFingerprint = fingerprint
                    recordSuccessfulSnapshotMutation(envelope, revision)
                    Telemetry.event(
                        "TimelineSync", "snapshotPersist.written",
                        "conversationId" to conversationId,
                        "revision" to revision,
                        "eventCount" to envelope.events.size,
                        "durationMs" to durationMs,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Telemetry.error(
                "TimelineSync", "snapshotPersist.failed", error,
                "conversationId" to conversationId,
                "revision" to revision,
            )
        }
    }

    private fun recordSuccessfulSnapshotMutation(envelope: StoredTimelineEnvelope, revision: Long) {
        val structuralSummary = TimelineSnapshotMutationCharacterizer.summarize(envelope)
        val mutationShape = TimelineSnapshotMutationCharacterizer.characterize(lastPersistedSnapshot, structuralSummary)
        lastPersistedSnapshot = structuralSummary
        Telemetry.event(
            "TimelineSync", "snapshotPersist.mutationShape",
            "revision" to revision,
            "previousCount" to mutationShape.previousCount,
            "eventCount" to mutationShape.currentCount,
            "inserted" to mutationShape.inserted,
            "updated" to mutationShape.updated,
            "deleted" to mutationShape.deleted,
            "moved" to mutationShape.moved,
            "cursorMetadataChanged" to mutationShape.cursorMetadataChanged,
            "noOp" to mutationShape.noOp,
            "unclassifiable" to mutationShape.unclassifiable,
            "comparisonEvents" to mutationShape.eventComparisons,
            "fullEnvelopeEncodes" to mutationShape.fullEnvelopeEncodes,
        )
    }

    // letta-mobile-dangling-tool: canonical-record-driven post-turn sweep +
    // hydration guard for tool-call cards left unresolved after PR #900
    // removed the guess-based settle-on-clean-completion behavior. See
    // DanglingToolCallResolver's kdoc for the never-guess principle.
    private val danglingToolCallResolver by lazy { DanglingToolCallResolver(
        conversationId = conversationId,
        processor = timelineProcessor,
        state = state,
        scope = loopScope,
        reconcile = { reason, forceRefresh ->
            when (val outcome = reconcileRecentMessages(reason, forceRefresh)) {
                is RecentMessagesReconcileOutcome.Applied -> outcome.appended
                is RecentMessagesReconcileOutcome.Skipped -> 0
                is RecentMessagesReconcileOutcome.Failed -> throw outcome.cause
            }
        },
        onSettlementCommitted = { scheduleSnapshotPersist(immediate = true) },
    ) }

    /** True while a turn is believed active for this conversation. Toggled by [turnStarted]/[turnEnded]. */
    @Volatile
    private var turnActive: Boolean = false

    private val timelineProcessor = TimelineProcessor(
        initialState = TimelineReducerState(initialTimeline ?: Timeline(conversationId)),
        scope = loopScope,
        effectHandler = { effect ->
            when (effect) {
                is TimelineReductionEffect.EmitSyncEvent -> _events.emit(effect.event)
                is TimelineReductionEffect.Notify -> ingestNotificationDispatcher.dispatch(effect.notification)
                is TimelineReductionEffect.Send -> outboundSendProcessor.sendQueue.send(effect.pending)
                is TimelineReductionEffect.PersistPendingLocal -> pendingLocalStore.save(
                    PendingLocalRecord(
                        otid = effect.pending.otid,
                        conversationId = conversationId,
                        content = effect.pending.content,
                        attachments = effect.pending.attachments,
                        sentAt = effect.sentAt,
                    ),
                )
                is TimelineReductionEffect.DeletePendingLocal -> pendingLocalStore.delete(effect.otid)
                is TimelineReductionEffect.RecordStreamSequence -> {
                    conversationCursorStore.recordFrame(conversationId, effect.sequence)
                }
                is TimelineReductionEffect.RepairHydrationCursor -> {
                    conversationCursorStore.recordFrame(conversationId, effect.sequence)
                    Telemetry.event(
                        "TimelineSync", "hydrate.cursorRepaired",
                        "conversationId" to conversationId,
                        "cursorSeq" to effect.sequence,
                    )
                }
                is TimelineReductionEffect.AdvanceCursor -> Unit
            }
        },
    )

    val state: StateFlow<Timeline> = timelineProcessor.timeline

    private val outboundSendProcessor = TimelineOutboundSendProcessor(
        conversationId = conversationId,
        messageApi = messageApi,
        eventQueue = eventQueue,
        state = state,
        events = _events,
        pendingLocalStore = pendingLocalStore,
        logTag = logTag,
        scope = loopScope,
        ingestStreamEvent = ::ingestStreamEvent,
        onSendStreamEnded = { wsSubscription.clear() },
        onTurnStarted = ::turnStarted,
        onTurnEnded = ::turnEnded,
    )

    private val stateTransitionHandler = TimelineStateTransitionHandler(
        conversationId = conversationId,
        processor = timelineProcessor,
    )

    private val externalTransportAppender = TimelineExternalTransportAppender(
        conversationId = conversationId,
        messageApi = messageApi,
        eventQueue = eventQueue,
        events = _events,
        processor = timelineProcessor,
        pendingLocalStore = pendingLocalStore,
        submitReconcileAfterSendSnapshot = ::submitReconcileAfterSendSnapshot
    )

    init {
        persistJob = loopScope.launch { runSnapshotPersistence() }
        eventProcessorJob = loopScope.launch { processEventQueue() }
        streamSubscriberJob = if (startStreamSubscriber) {
            loopScope.launch { runStreamSubscriber() }
        } else {
            null
        }
    }

    suspend fun emitHydrateFailed(message: String) {
        _events.emit(TimelineSyncEvent.HydrateFailed(message))
    }

    fun close() {
        persistRequests.close()
        eventQueue.close(CancellationException("TimelineSyncLoop closed"))
        outboundSendProcessor.sendQueue.close(CancellationException("TimelineSyncLoop closed"))
        timelineProcessor.close()
        loopJob.cancel(CancellationException("TimelineSyncLoop closed"))
    }

    suspend fun closeAndJoin() {
        persistRequests.close()
        eventQueue.close()
        streamSubscriberJob?.cancel(CancellationException("TimelineSyncLoop draining"))
        withContext(NonCancellable) {
            streamSubscriberJob?.join()
            eventProcessorJob.join()
            timelineProcessor.closeAndJoin()
            persistJob.join()
            flushSnapshotNow(prune = true)
        }
        close()
    }

    @Volatile
    var hasHydratedSuccessfully: Boolean = false
        private set

    suspend fun hydrate(limit: Int = 50, recordConversationCursor: Boolean = false, fallbackCursorSeq: Long? = null) {
        when (hydrator.hydrate(limit, recordConversationCursor, fallbackCursorSeq)) {
            TimelineHydrationOutcome.Rejected -> Unit
            TimelineHydrationOutcome.Accepted,
            TimelineHydrationOutcome.DefaultShimAccepted,
            -> {
                hasHydratedSuccessfully = true
                // letta-mobile-dangling-tool: heal stale spinners that survived an
                // app restart or a dropped stream. Escalates to the same bounded
                // backoff sweep as turnEnded if the immediate reconcile alone
                // doesn't resolve everything, so there's always a terminal outcome.
                danglingToolCallResolver.runHydrationGuardIfIdle(turnActive)
            }
        }
    }

    /**
     * Signals that a turn has started for this conversation. A new turn
     * supersedes whatever the previous turn's sweep left pending — see
     * [DanglingToolCallResolver.cancelPendingSweep].
     */
    suspend fun turnStarted() {
        turnActive = true
        danglingToolCallResolver.cancelPendingSweep()
    }

    /**
     * Signals that a turn has ended for this conversation. Schedules the
     * bounded canonical-record-driven sweep whenever unresolved tool-call
     * cards remain — on EVERY completion path, clean or abnormal.
     *
     * [clean] is passed through to the resolver for telemetry only; it does
     * NOT gate whether the sweep is scheduled (Codex #902 finding 3). If it
     * did, an abnormal (cancel/timeout/error) completion of turn N+1 would
     * call [DanglingToolCallResolver.cancelPendingSweep] on `turnStarted()`
     * for N+1 and then never reschedule anything on its own abnormal
     * `turnEnded(clean = false)`, permanently dropping turn N's still-
     * dangling sweep. Always scheduling is safe because the sweep only ever
     * asks the canonical record — an abnormal turn's own calls are already
     * settled synchronously by AppServerTurnEngine, so they never appear in
     * [Timeline.unresolvedToolCallIds] to begin with.
     */
    suspend fun turnEnded(clean: Boolean) {
        turnActive = false
        danglingToolCallResolver.scheduleSweepIfUnresolved(clean)
    }

    suspend fun send(content: String, attachments: List<MessageContentPart.Image> = emptyList()): String {
        return outboundSendProcessor.send(content, attachments)
    }

    /**
     * letta-mobile-mxwtn: pre-minted-otid send entry point that the platform
     * send coordinator uses AFTER it has already inserted the Local event
     * synchronously via [appendOptimisticLocalSync]. The Local append is
     * skipped on this path — see [TimelineOutboundSendProcessor.sendWithOtid]
     * for the lifecycle invariants.
     */
    suspend fun sendWithOtid(
        otid: String,
        content: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ) {
        outboundSendProcessor.sendWithOtid(otid, content, attachments, appendLocal = false)
    }

    /**
     * letta-mobile-mxwtn: synchronous optimistic Local append. Writes a
     * `TimelineEvent.Local` with the given otid through [TimelineProcessor]
     * and returns `true`. Idempotent: returns
     * `false` if an event with the same otid is already present, so a
     * duplicate caller cannot fork the timeline.
     */
    suspend fun appendOptimisticLocalSync(
        otid: String,
        content: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): Boolean {
        recentMessagesReconciler.invalidateFreshness()
        return stateTransitionHandler.appendOptimisticLocalSync(
            otid = otid,
            content = content,
            attachments = attachments.toTimelinePersistentList(),
            sentAt = timelineNow(),
        )
    }

    /** letta-mobile-mxwtn: synchronous SENT transition on a Local event. */
    suspend fun markOptimisticLocalSentSync(otid: String) {
        stateTransitionHandler.markOptimisticLocalSentSync(otid)
    }

    /** letta-mobile-mxwtn: synchronous FAILED transition on a Local event. */
    suspend fun markOptimisticLocalFailedSync(otid: String) {
        stateTransitionHandler.markOptimisticLocalFailedSync(otid)
    }

    suspend fun appendExternalTransportLocal(content: String, otid: String, attachments: List<MessageContentPart.Image> = emptyList()): String {
        return externalTransportAppender.appendExternalTransportLocal(
            TimelineExternalAppendRequest(content, TimelineExternalOtid(otid), attachments),
        )
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
                            recentMessagesReconciler.invalidateFreshness()
                            streamDispatcher.dispatch(event.message, event.source)
                            event.ack?.complete(Unit)
                        }
                    }
                    is TimelineGatewayEvent.LocalSendAppend -> {
                        recentMessagesReconciler.invalidateFreshness()
                        try {
                            stateTransitionHandler.applyLocalSendAppend(event)
                        } finally {
                            scheduleSnapshotPersist(immediate = true)
                        }
                    }
                    is TimelineGatewayEvent.ExternalTransportLocalAppend -> {
                        recentMessagesReconciler.invalidateFreshness()
                        try {
                            externalTransportAppender.applyExternalTransportLocalAppend(event)
                        } finally {
                            scheduleSnapshotPersist(immediate = true)
                        }
                    }
                    is TimelineGatewayEvent.ReconcileAfterSendSnapshot -> applyReconcileAfterSendSnapshot(event)
                    is TimelineGatewayEvent.RecentMessagesSnapshot -> recentMessagesReconciler.applyRecentMessagesSnapshot(event)
                    is TimelineGatewayEvent.PostHandlerCollapse -> event.ack.complete(Unit)
                    is TimelineGatewayEvent.RetrySend -> {
                        recentMessagesReconciler.invalidateFreshness()
                        try {
                            stateTransitionHandler.applyRetrySend(event)
                        } finally {
                            scheduleSnapshotPersist(immediate = true)
                        }
                    }
                    is TimelineGatewayEvent.MarkSent -> {
                        try {
                            stateTransitionHandler.applyMarkSent(event)
                        } finally {
                            scheduleSnapshotPersist(immediate = true)
                        }
                    }
                    is TimelineGatewayEvent.MarkFailed -> {
                        try {
                            stateTransitionHandler.applyMarkFailed(event)
                        } finally {
                            scheduleSnapshotPersist(immediate = true)
                        }
                    }
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
        val applied = timelineProcessor.submit(
            TimelineMutation.ReconcileAfterSendSnapshot(event.otid, event.serverMessages),
        )
        when (applied) {
            is TimelineProcessorAck.Applied -> {
                val result = applied.result as? TimelineReductionResult.ReconcileAfterSendApplied
                    ?: error("post-send acknowledgement did not carry reconcile result")
                if (result.changed) scheduleSnapshotPersist(immediate = true)
                event.ack.complete(result.result)
            }
            is TimelineProcessorAck.Rejected,
            is TimelineProcessorAck.Failed -> event.ack.completeExceptionally(
                TimelineProcessorMutationException("post-send reconciliation was not applied: $applied"),
            )
        }
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
        when (val ack = timelineProcessor.submitMaintenanceMutation(
            TimelineMutation.CleanupAbandonedFragments(
                runId = event.runId,
                turnId = event.turnId,
                reason = event.reason,
                candidateRunIds = event.candidateRunIds,
            ),
        )) {
            is TimelineProcessorAck.Applied -> {
                val result = ack.result as? TimelineReductionResult.CleanupApplied
                val removed = result?.removed ?: 0
                if (result?.changed == true) {
                    scheduleSnapshotPersist(immediate = true)
                    if (!event.runId.isNullOrBlank()) {
                        _events.emit(TimelineSyncEvent.OrphanAssistantFragmentsCleaned(event.runId, event.turnId, removed, event.reason))
                    }
                }
                event.ack.complete(removed)
            }
            is TimelineProcessorAck.Rejected -> event.ack.completeExceptionally(
                TimelineProcessorMutationException("cleanup was not applied: $ack"),
            )
            is TimelineProcessorAck.Failed -> event.ack.completeExceptionally(
                TimelineProcessorMutationException("cleanup was not applied: $ack"),
            )
        }
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
        val applied = timelineProcessor.submitMaintenanceMutation(TimelineMutation.RepairFullToolReturn(message))
        val result = (applied as? TimelineProcessorAck.Applied)?.result as? TimelineReductionResult.FullToolReturnRepaired
            ?: return false
        if (!result.changed) return false
        scheduleSnapshotPersist(immediate = true)
        Telemetry.event(
            "TimelineSync", "toolReturn.resolved",
            "conversationId" to conversationId,
            "messageId" to messageId,
            "bodyLen" to (message.toolReturn.funcResponse?.length ?: 0),
        )
        return true
    }

    suspend fun reconcileForExternalRun(runId: String) {
        reconcileForExternalRun(runId) { name, _, allowWhileActive ->
            val outcome = recentMessagesReconciler.reconcileRecentMessages(
                reason = name,
                forceRefresh = allowWhileActive,
            )
            if (outcome is RecentMessagesReconcileOutcome.Failed) throw outcome.cause
        }
    }

    suspend fun reconcileRecentMessages(
        reason: String,
        forceRefresh: Boolean = false,
        connectionGeneration: Long = 0L,
    ): RecentMessagesReconcileOutcome {
        return recentMessagesReconciler.reconcileRecentMessages(reason, forceRefresh, connectionGeneration)
    }

    suspend fun markExternalTransportLocalSent(otid: String) {
        externalTransportAppender.markExternalTransportLocalSent(TimelineExternalOtid(otid))
    }

    suspend fun markExternalTransportLocalFailed(otid: String) {
        externalTransportAppender.markExternalTransportLocalFailed(TimelineExternalOtid(otid))
    }

    suspend fun reconcileExternalTransportSend(agentId: String, externalConversationId: String, otid: String) {
        externalTransportAppender.reconcileExternalTransportSend(
            TimelineExternalReconcileRequest(agentId, externalConversationId, TimelineExternalOtid(otid)),
        )
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
        // letta-mobile-c4igq.4: forward this loop owning agentId to the reducer.
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

    private enum class SnapshotPersistRequest {
        Immediate,
        Debounced,
    }

    companion object {
        private val SNAPSHOT_PERSIST_DEBOUNCE = 100.milliseconds
        private const val STREAM_HEARTBEAT_EXPECTED_MS = 30_000L
        // letta-mobile-5pi: 6x multiplier = 3 minute silence timeout.
        // Previously 12x (6 minutes) — a dead stream could go undetected
        // for too long. 6x still tolerates 6 missed heartbeats (plenty of
        // margin for network jitter) while detecting stuck streams faster.
        private const val STREAM_SILENCE_TIMEOUT_MS = STREAM_HEARTBEAT_EXPECTED_MS * 6
        private const val GATEWAY_EVENT_CAPACITY = 64
        private const val MAX_SEEN_STREAM_MESSAGES = 512
        private const val MAX_RETAINED_SNAPSHOTS = 50
        private val activeStreamCount = TimelineAtomicCounter(0)
        internal val DEFAULT_INCLUDE_TYPES = listOf("assistant_message", "reasoning_message", "tool_call_message", "tool_return_message")
    }
}
