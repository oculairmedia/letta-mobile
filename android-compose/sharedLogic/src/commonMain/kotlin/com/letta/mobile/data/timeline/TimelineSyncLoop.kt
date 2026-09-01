package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.NoOpConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlanner
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult
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
    // The last durably-acknowledged full envelope (from repository hydration), used as the
    // incremental commit planner's structural baseline. Without this, a loop created after a
    // process restart would plan every mutation against `previous = null`, i.e. baseRevision
    // 0, and the very first commit would be rejected Stale by the store's CAS check against
    // the already-durable revision from the prior session.
    initialPersistedEnvelope: StoredTimelineEnvelope? = null,
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
    // Full envelope of the last durably-committed (normalized OR legacy) write. This is the
    // incremental commit planner's `previous`. Only successful commits/no-ops advance it;
    // stale/invalid/failed attempts never do (letta-mobile-827s9.4 requirement 5).
    private var lastPersistedEnvelope: StoredTimelineEnvelope? = initialPersistedEnvelope
    // Legacy v11 checkpoint cadence: write a full envelope (readable by rollback/older builds)
    // every LEGACY_CHECKPOINT_INTERVAL successful normalized commits, plus always on the very
    // first commit for a scope. Bounds legacy staleness to at most that many revisions.
    private var commitsSinceLegacyCheckpoint: Int = 0
    // Set when a persist was suppressed during an active turn, so the per-turn safety timer
    // only writes when there is actually something deferred to write.
    @Volatile
    private var deferredDuringTurn: Boolean = false
    private var turnSafetyFlushJob: Job? = null
    // Consecutive Stale results. Reset by any durable commit; a duplicate holder never
    // resets and so detaches quickly, while a transient loser recovers.
    private var consecutiveStaleRejections: Int = 0
    // Set once this loop is proven not to own its conversation's durable state (see
    // onStaleRejection). A detached loop still serves reads; it just stops writing.
    @Volatile
    private var detachedAsStaleWriter: Boolean = false
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
            onStreamFrameIngested = { scheduleSnapshotPersist(SnapshotPersistReason.STREAM_FRAME) },
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
            onSnapshotApplied = { scheduleSnapshotPersist(SnapshotPersistReason.RECONCILE) },
        )
    }

    private val hydrator by lazy {
        TimelineHydrator(
            conversationId = conversationId,
            messageApi = messageApi,
            pendingLocalStore = pendingLocalStore,
            events = _events,
            timelineProcessor = timelineProcessor,
            onHydrationCommitted = { scheduleSnapshotPersist(SnapshotPersistReason.HYDRATION) },
        )
    }

    /**
     * letta-mobile-827s9.4, dogfood round 3 item 1: every scheduling source is now TYPED.
     *
     * The previous boolean gated only `Debounced` requests, and 12 of the 13 call sites passed
     * `immediate = true` -- so hydration, reconcile, cursor repair and local-mutation callbacks
     * all bypassed the streaming deferral entirely. That is why the capture still showed 12
     * commits in 106 s, including a background conversation committing 11 times at 182-207 ms
     * each while a different conversation was under test.
     *
     * During an active turn only [SnapshotPersistReason.isTurnBoundary] reasons may write.
     * Everything else coalesces behind that boundary instead of jumping it.
     */
    internal fun scheduleSnapshotPersist(reason: SnapshotPersistReason) {
        timelineScope ?: return
        if (confirmedTimelineStore === NoOpConfirmedTimelineStore) return
        if (detachedAsStaleWriter) return
        if (turnActive && !reason.isTurnBoundary) {
            Telemetry.event(
                "TimelineSync", "snapshotPersist.streamingDeferred",
                "conversationId" to conversationId,
                "agentId" to agentId.orEmpty(),
                "reason" to reason.name,
            )
            deferredDuringTurn = true
            armSafetyFlushDeadline()
            return
        }
        Telemetry.event(
            "TimelineSync", "snapshotPersist.scheduled",
            "conversationId" to conversationId,
            "agentId" to agentId.orEmpty(),
            "reason" to reason.name,
            "turnActive" to turnActive,
        )
        persistRequests.trySend(
            if (reason.isDebounced) SnapshotPersistRequest.Debounced else SnapshotPersistRequest.Immediate,
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
            // Deferral is decided at SCHEDULING time by reason now, not here on arrival --
            // arrival-based gating let unrelated later requests decide when a write happened,
            // which is what produced the irregular 2.5-17 s commit spacing in the capture.
            flushSnapshotNow(prune = true)
        }
    }


    suspend fun flushSnapshotNow(prune: Boolean = false) {
        val snapshotScope = timelineScope ?: return
        if (confirmedTimelineStore === NoOpConfirmedTimelineStore) return
        // Round 4: the detach guard was only on scheduleSnapshotPersist, so every direct
        // caller of this -- including closeAndJoin -- could still run a full O(N) plan and a
        // rejected commit after the loop had been proven not to own its conversation. Returning
        // BEFORE the mutex and the planner is the point: a detached writer must cost nothing,
        // not merely fail cheaply at the store.
        if (detachedAsStaleWriter) return
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

        // PM review item 2: allocate the candidate revision WITHOUT mutating
        // snapshotRevision. It is committed only on a successful Committed/NoOp result
        // below. Incrementing here meant a Stale/Invalid/failed attempt burned a
        // revision while lastPersistedEnvelope stayed put: the retry still converged
        // (the CAS base comes from the acknowledged envelope, not from this counter)
        // but the emitted revision sequence developed permanent gaps, which makes
        // "did we skip a write?" unanswerable from telemetry. Success-safe ownership
        // instead: no durable write, no revision consumed.
        val revision = snapshotRevision + 1
        val envelope = provisionalEnvelope.copy(revision = revision)
        val startedAtMs = timelineCurrentTimeMillis()
        val plan = NormalizedTimelineCommitPlanner.plan(lastPersistedEnvelope, envelope)
        val checkpointDue = isLegacyCheckpointDue(plan)

        try {
            withContext(ioDispatcher + NonCancellable) {
                val result = confirmedTimelineStore.commitNormalized(plan, envelope, checkpointDue)
                val durationMs = timelineCurrentTimeMillis() - startedAtMs
                when (result) {
                    is NormalizedTimelineWriteResult.Committed, is NormalizedTimelineWriteResult.NoOp ->
                        onDurableCommit(result, envelope, revision, fingerprint, checkpointDue, durationMs)
                    is NormalizedTimelineWriteResult.Stale -> onStaleRejection(result, revision)
                    is NormalizedTimelineWriteResult.Invalid ->
                        onInvalidPlan(result, envelope, revision, fingerprint)
                }
                if (prune) {
                    confirmedTimelineStore.prune(snapshotScope.backendId, MAX_RETAINED_SNAPSHOTS)
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

    /**
     * Legacy v11 checkpoint policy: always checkpoint the very first commit for a scope (so a
     * brand-new conversation has an immediately rollback-readable legacy envelope), then every
     * [LEGACY_CHECKPOINT_INTERVAL] successful commits after that. Bounds legacy staleness to at
     * most that many revisions. This is only a *hint* passed to
     * [ConfirmedTimelineStore.commitNormalized]; whether/how a checkpoint is actually staged
     * (and that a checkpoint failure never fails an otherwise-successful normalized commit) is
     * the store implementation's responsibility -- see the Android Room
     * `RoomConfirmedTimelineStore.commitNormalized` implementation for the real
     * incremental-commit + best-effort-checkpoint behavior.
     */
    private fun isLegacyCheckpointDue(plan: NormalizedTimelineCommitPlan): Boolean {
        val isInitialCommit = plan is NormalizedTimelineCommitPlan.Apply && plan.commit.baseRevision.value == 0L
        return isInitialCommit || commitsSinceLegacyCheckpoint + 1 >= LEGACY_CHECKPOINT_INTERVAL
    }

    /**
     * letta-mobile-827s9.4, dogfood round 2, item 2: a stale writer must STOP, not spin.
     *
     * The Pixel capture showed repeated `revision=1 actualHighWaterRevision=...` rejections
     * from holders that could never commit. Each rejection still cost a full O(N) plan over a
     * 2k-event timeline, so a duplicate holder burned real CPU and heap producing nothing,
     * concurrently with the visible conversation's own commits.
     *
     * A `Stale` result means another holder owns this conversation's durable state. Retrying
     * cannot help: this loop's acknowledged baseline is behind and nothing in this loop will
     * advance it. So the loop detaches as a writer — it keeps serving reads and its in-memory
     * timeline, but stops scheduling persists entirely.
     *
     * NOTE this is a bounded mitigation, not the cure. The duplicate holders themselves are
     * letta-mobile-grrhq: a subagent dispatch acquires a SECOND holder for the parent's
     * conversation under the child agent id. This stops the wasted work; grrhq stops the
     * second holder existing.
     */
    private fun onStaleRejection(result: NormalizedTimelineWriteResult.Stale, revision: Long) {
        // Round 4: detach after a BOUNDED run of consecutive stales, not the first one.
        //
        // First-stale detachment is the stricter reading of the review, and I implemented it
        // that way initially -- but it permanently stops a writer that lost a single transient
        // race, and it broke a documented pre-existing behaviour (a rejected write followed by
        // a successful retry). Trading a durability guarantee for CPU is the wrong direction;
        // the goal was to stop UNBOUNDED spinning, and a small cap does that. A duplicate
        // holder never succeeds, so it still detaches almost immediately; a transient loser
        // recovers on its next attempt and resets the counter.
        consecutiveStaleRejections += 1
        val detaching = consecutiveStaleRejections >= MAX_CONSECUTIVE_STALE_REJECTIONS
        if (detaching) {
            detachedAsStaleWriter = true
            // Drain anything already queued so the detach takes effect immediately.
            while (persistRequests.tryReceive().isSuccess) Unit
        }
        Telemetry.event(
            "TimelineSync", "snapshotPersist.staleRejected",
            "conversationId" to conversationId,
            "agentId" to agentId.orEmpty(),
            "revision" to revision,
            "actualHighWaterRevision" to result.highWaterRevision.value,
            "consecutiveStale" to consecutiveStaleRejections,
            "detachedAsWriter" to detaching,
            level = Telemetry.Level.WARN,
        )
    }

    /**
     * The durable write landed. Only here is the candidate revision consumed and the
     * acknowledged baseline advanced -- see the allocation comment in
     * [persistCurrentSnapshot] for why nothing above this point may mutate them.
     */
    private fun onDurableCommit(
        result: NormalizedTimelineWriteResult,
        envelope: StoredTimelineEnvelope,
        revision: Long,
        fingerprint: Long,
        checkpointDue: Boolean,
        durationMs: Long,
    ) {
        consecutiveStaleRejections = 0
        snapshotRevision = revision
        lastPersistedFingerprint = fingerprint
        lastPersistedEnvelope = envelope
        recordSuccessfulSnapshotMutation(envelope, revision)
        commitsSinceLegacyCheckpoint = if (checkpointDue) 0 else commitsSinceLegacyCheckpoint + 1
        Telemetry.event(
            "TimelineSync", "snapshotPersist.written",
            "conversationId" to conversationId,
            "revision" to revision,
            "eventCount" to envelope.events.size,
            "durationMs" to durationMs,
            "committed" to (result is NormalizedTimelineWriteResult.Committed),
        )
    }

    /**
     * An `Invalid` plan is NOT transient: an oversized event row makes every subsequent plan
     * for this conversation Invalid too, so logging alone meant the conversation silently
     * stopped being durable forever -- the worst shape a persistence bug can take, because
     * nothing ever surfaces.
     *
     * Explicit bounded fallback: write the full legacy v11 envelope so durable progress is
     * preserved. Its high-water revision then exceeds the normalized head, and
     * `readSnapshotResult`'s freshness comparison serves legacy until normalized catches up,
     * so the two stores stay coherent. This is the ONLY path that still performs
     * full-envelope encoding on an ordinary mutation, and it is reached only when incremental
     * commit is structurally impossible.
     */
    private suspend fun onInvalidPlan(
        result: NormalizedTimelineWriteResult.Invalid,
        envelope: StoredTimelineEnvelope,
        revision: Long,
        fingerprint: Long,
    ) {
        val recovered = confirmedTimelineStore.writeSnapshot(envelope)
        if (recovered) {
            snapshotRevision = revision
            lastPersistedFingerprint = fingerprint
            lastPersistedEnvelope = envelope
            recordSuccessfulSnapshotMutation(envelope, revision)
            commitsSinceLegacyCheckpoint = 0
        }
        Telemetry.event(
            "TimelineSync", "snapshotPersist.invalidRejected",
            "conversationId" to conversationId,
            "revision" to revision,
            "reason" to result.reason.name,
            "legacyFallbackWritten" to recovered,
            level = Telemetry.Level.WARN,
        )
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
        onSettlementCommitted = { scheduleSnapshotPersist(SnapshotPersistReason.SETTLEMENT) },
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
        deferredDuringTurn = false
        startTurnSafetyFlushTimer()
        danglingToolCallResolver.cancelPendingSweep()
    }

    /**
     * letta-mobile-827s9.4, dogfood round 3 item 2: a real per-turn DEADLINE, not
     * request-arrival behaviour.
     *
     * The previous safety flush only took effect when some later request happened to arrive
     * after the window had elapsed, so unrelated callbacks decided when the write happened.
     * That is why the capture showed irregular 2.5-17 s spacing instead of a bound. A deferred
     * request now enqueues nothing; this timer owns the boundary, firing at most once per
     * [STREAMING_SAFETY_FLUSH] and only when something was actually deferred.
     */
    private fun startTurnSafetyFlushTimer() {
        turnSafetyFlushJob?.cancel()
        turnSafetyFlushJob = null
    }

    /**
     * Arms a ONE-SHOT deadline the first time a persist is deferred in this turn.
     *
     * Deliberately not a `while (turnActive) { delay(...) }` poll. A never-completing delay
     * loop makes `advanceUntilIdle()` spin forever in tests, and in production it wakes every
     * 5 s for the whole turn even when nothing was deferred. One-shot, re-armed only by the
     * next deferral, costs nothing on an idle conversation and terminates.
     */
    private fun armSafetyFlushDeadline() {
        if (turnSafetyFlushJob?.isActive == true) return
        turnSafetyFlushJob = loopScope.launch {
            delay(STREAMING_SAFETY_FLUSH)
            if (turnActive && deferredDuringTurn) {
                deferredDuringTurn = false
                scheduleSnapshotPersist(SnapshotPersistReason.SAFETY_FLUSH)
            }
        }
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
        turnSafetyFlushJob?.cancel()
        turnSafetyFlushJob = null
        // letta-mobile-827s9.4: this is the settled boundary the streaming defer in
        // shouldDeferStreamingPersist relies on. Without it, a turn whose final delta was
        // deferred stays memory-only until some unrelated trigger happens along -- the
        // deferral would be trading a real durability guarantee for frame rate, which is not
        // the bargain. Scheduled AFTER clearing turnActive so it is never itself deferred.
        scheduleSnapshotPersist(SnapshotPersistReason.TURN_END)
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
                            scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
                        }
                    }
                    is TimelineGatewayEvent.ExternalTransportLocalAppend -> {
                        recentMessagesReconciler.invalidateFreshness()
                        try {
                            externalTransportAppender.applyExternalTransportLocalAppend(event)
                        } finally {
                            scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
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
                            scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
                        }
                    }
                    is TimelineGatewayEvent.MarkSent -> {
                        try {
                            stateTransitionHandler.applyMarkSent(event)
                        } finally {
                            scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
                        }
                    }
                    is TimelineGatewayEvent.MarkFailed -> {
                        try {
                            stateTransitionHandler.applyMarkFailed(event)
                        } finally {
                            scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
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
                if (result.changed) scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
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
                    scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
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
        scheduleSnapshotPersist(SnapshotPersistReason.LOCAL_MUTATION)
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

        /**
         * Upper bound on how long a streaming turn may go without a durable write. Large
         * enough that an ordinary turn persists once at settlement rather than dozens of
         * times mid-stream; small enough that a very long response is never wholly at risk.
         */
        internal val STREAMING_SAFETY_FLUSH = 5_000.milliseconds

        /** Bound on wasted planning by a writer that cannot commit. */
        internal const val MAX_CONSECUTIVE_STALE_REJECTIONS = 3
        private const val STREAM_HEARTBEAT_EXPECTED_MS = 30_000L
        // letta-mobile-5pi: 6x multiplier = 3 minute silence timeout.
        // Previously 12x (6 minutes) — a dead stream could go undetected
        // for too long. 6x still tolerates 6 missed heartbeats (plenty of
        // margin for network jitter) while detecting stuck streams faster.
        private const val STREAM_SILENCE_TIMEOUT_MS = STREAM_HEARTBEAT_EXPECTED_MS * 6
        private const val GATEWAY_EVENT_CAPACITY = 64
        private const val MAX_SEEN_STREAM_MESSAGES = 512
        private const val MAX_RETAINED_SNAPSHOTS = 50
        // letta-mobile-827s9.4: bounds legacy v11 checkpoint staleness to at most this many
        // normalized commits (plus the always-checkpointed initial commit for a scope).
        internal const val LEGACY_CHECKPOINT_INTERVAL = 25
        private val activeStreamCount = TimelineAtomicCounter(0)
        internal val DEFAULT_INCLUDE_TYPES = listOf("assistant_message", "reasoning_message", "tool_call_message", "tool_return_message")
    }
}
