package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lifts recent-messages synchronization: periodic reconciles + snapshot applications.
 */
sealed interface RecentMessagesReconcileOutcome {
    data class Applied(val appended: Int) : RecentMessagesReconcileOutcome
    data class Skipped(val reason: String) : RecentMessagesReconcileOutcome
    data class Failed(val cause: Throwable) : RecentMessagesReconcileOutcome
}

class TimelineRecentMessagesReconciler(
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val messageApi: TimelineTransport,
    private val eventQueue: Channel<TimelineGatewayEvent>,
    private val state: StateFlow<Timeline>,
    private val streamSubscriberActive: StateFlow<Boolean>,
    private val processor: TimelineProcessor,
    private val onSnapshotApplied: () -> Unit,
    private val nowMillis: () -> Long = { timelineCurrentTimeMillis() },
    private val minForcedReconcileIntervalMs: Long = DEFAULT_MIN_FORCED_RECONCILE_INTERVAL_MS,
) {
    val seenRunIds = TimelineSeenRunTracker()

    private data class ReconcileRequest(
        val reason: String,
        val connectionGeneration: Long,
        val allowWhileStreamActive: Boolean,
    )

    private data class ReconcileFlight(
        val request: ReconcileRequest,
        val result: CompletableDeferred<RecentMessagesReconcileOutcome>,
    )

    private data class FlightClaim(
        val flight: ReconcileFlight,
        val retryAfterward: Boolean,
    )

    private data class RecentSuccess(
        val completedAtMs: Long,
        val connectionGeneration: Long,
        val appended: Int,
        val freshnessSeq: Long,
    )

    private data class FreshnessState(
        val sequence: Long = 0L,
        val recentSuccess: RecentSuccess? = null,
    )

    private val reconcileFlightMutex = Mutex()
    private val freshnessState = atomic(FreshnessState())
    private val lastForcedReconcileCompletedAtMsByGeneration = mutableMapOf<Long, Long>()
    private var inFlight: ReconcileFlight? = null
    private var latestRequestedGeneration: Long = DEFAULT_CONNECTION_GENERATION

    fun invalidateFreshness() {
        updateFreshnessState { current -> current.copy(sequence = current.sequence + 1L) }
    }

    private inline fun updateFreshnessState(transform: (FreshnessState) -> FreshnessState) {
        while (true) {
            val current = freshnessState.value
            if (freshnessState.compareAndSet(current, transform(current))) return
        }
    }

    suspend fun reconcileRecentMessages(
        reason: String,
        forceRefresh: Boolean = false,
        connectionGeneration: Long = DEFAULT_CONNECTION_GENERATION,
    ): RecentMessagesReconcileOutcome {
        val resolvedGeneration = connectionGeneration.takeIf { it > DEFAULT_CONNECTION_GENERATION }
            ?: processor.state.value.highestAppliedReconcileGeneration
        val request = ReconcileRequest(reason, resolvedGeneration, forceRefresh)
        recentSuccessFor(request)?.let { return it }

        while (true) {
            val claim = claimFlight(request)
            val outcome = claim.flight.result.await()
            if (!claim.retryAfterward) return outcome
        }
    }

    private fun recentSuccessFor(request: ReconcileRequest): RecentMessagesReconcileOutcome.Applied? {
        val freshness = freshnessState.value
        val success = freshness.recentSuccess ?: return null
        if (!request.canReuse(success, freshness.sequence, nowMillis())) return null
        Telemetry.event(
            "TimelineSync", "recentReconcile.coalesced",
            "conversationId" to conversationId,
            "reason" to request.reason,
            "generation" to request.connectionGeneration,
            "coalescedWith" to "recent_successful_reconcile",
            "appended" to success.appended,
        )
        return RecentMessagesReconcileOutcome.Applied(success.appended)
    }

    private suspend fun claimFlight(request: ReconcileRequest): FlightClaim = reconcileFlightMutex.withLock {
        if (request.isOlderThan(latestRequestedGeneration)) {
            Telemetry.event(
                "TimelineSync", "recentReconcile.staleGenerationRejected",
                "conversationId" to conversationId,
                "reason" to request.reason,
                "generation" to request.connectionGeneration,
                "latestGeneration" to latestRequestedGeneration,
                level = Telemetry.Level.WARN,
            )
            val rejected = CompletableDeferred<RecentMessagesReconcileOutcome>()
            rejected.complete(RecentMessagesReconcileOutcome.Skipped("staleGeneration"))
            return@withLock FlightClaim(ReconcileFlight(request, rejected), retryAfterward = false)
        }
        latestRequestedGeneration = maxOf(latestRequestedGeneration, request.connectionGeneration)
        val current = inFlight
        if (current == null || current.result.isCompleted) {
            val flight = ReconcileFlight(request, CompletableDeferred())
            inFlight = flight
            scope.launch(start = CoroutineStart.UNDISPATCHED) { executeClaimedFlight(flight) }
            return@withLock FlightClaim(flight, retryAfterward = false)
        }

        val retryAfterward = !current.request.satisfies(request)
        Telemetry.event(
            "TimelineSync",
            if (retryAfterward) "recentReconcile.superseded" else "recentReconcile.coalesced",
            "conversationId" to conversationId,
            "reason" to request.reason,
            "generation" to request.connectionGeneration,
            "inFlightGeneration" to current.request.connectionGeneration,
            "inFlightForced" to current.request.allowWhileStreamActive,
        )
        FlightClaim(current, retryAfterward)
    }

    private suspend fun executeClaimedFlight(flight: ReconcileFlight) {
        val freshnessSeqAtStart = freshnessState.value.sequence
        try {
            val outcome = executeReconcileFromServer(flight.request)
            recordRecentSuccess(flight.request, outcome, freshnessSeqAtStart)
            flight.result.complete(outcome)
        } catch (cancelled: CancellationException) {
            flight.result.completeExceptionally(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            flight.result.completeExceptionally(error)
            throw error
        } finally {
            withContext(NonCancellable) {
                reconcileFlightMutex.withLock {
                    if (inFlight === flight) inFlight = null
                }
            }
        }
    }

    private suspend fun executeReconcileFromServer(request: ReconcileRequest): RecentMessagesReconcileOutcome =
        reconcileRecentMessagesFromServer(
            telemetryName = "recentReconcile",
            telemetryReason = request.reason,
            allowWhileStreamActive = request.allowWhileStreamActive,
            connectionGeneration = request.connectionGeneration,
        )

    private fun recordRecentSuccess(
        request: ReconcileRequest,
        outcome: RecentMessagesReconcileOutcome,
        freshnessSeqAtStart: Long,
    ) {
        if (outcome !is RecentMessagesReconcileOutcome.Applied) return
        val success = RecentSuccess(
            completedAtMs = nowMillis(),
            connectionGeneration = request.connectionGeneration,
            appended = outcome.appended,
            freshnessSeq = freshnessSeqAtStart,
        )
        updateFreshnessState { current -> current.copy(recentSuccess = success) }
    }

    private fun ReconcileRequest.satisfies(request: ReconcileRequest): Boolean =
        connectionGeneration >= request.connectionGeneration &&
            (allowWhileStreamActive || !request.allowWhileStreamActive)

    private fun ReconcileRequest.isOlderThan(generation: Long): Boolean =
        connectionGeneration > DEFAULT_CONNECTION_GENERATION && connectionGeneration < generation

    private suspend fun isSupersededGeneration(connectionGeneration: Long): Boolean =
        connectionGeneration > DEFAULT_CONNECTION_GENERATION &&
            reconcileFlightMutex.withLock { connectionGeneration < latestRequestedGeneration }

    private fun ReconcileRequest.canReuse(
        success: RecentSuccess,
        currentFreshnessSeq: Long,
        currentTimeMs: Long,
    ): Boolean =
        reason in RESUME_REASONS &&
            !allowWhileStreamActive &&
            connectionGeneration == success.connectionGeneration &&
            currentFreshnessSeq == success.freshnessSeq &&
            currentTimeMs - success.completedAtMs < DEFAULT_FRESHNESS_WINDOW_MS

    suspend fun reconcileRecentMessagesFromServer(
        telemetryName: String,
        telemetryReason: String,
        allowWhileStreamActive: Boolean = false,
        connectionGeneration: Long = DEFAULT_CONNECTION_GENERATION,
    ): RecentMessagesReconcileOutcome {
        val timer = Telemetry.startTimer("TimelineSync", telemetryName)
        val isForcedWhileActive = streamSubscriberActive.value && allowWhileStreamActive
        val skipReason = skipReasonFor(allowWhileStreamActive, isForcedWhileActive, connectionGeneration)
        if (skipReason != null) return skipReconcile(timer, telemetryName, telemetryReason, skipReason)
        return try {
            val result = fetchAndApplySnapshot(telemetryName, telemetryReason, connectionGeneration)
                ?: return skipReconcile(timer, telemetryName, telemetryReason, "supersededGeneration")
            val (serverCount, appended) = result
            onForcedReconcileCompleted(isForcedWhileActive, connectionGeneration)
            timer.stop("reason" to telemetryReason, "serverCount" to serverCount, "appended" to appended)
            dumpTimelineState("reconcile.$telemetryName", conversationId, state.value)
            RecentMessagesReconcileOutcome.Applied(appended)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            timer.stopError(t, "reason" to telemetryReason)
            RecentMessagesReconcileOutcome.Failed(t)
        }
    }

    /**
     * A forced reconcile (post-send retries, redial recovery) is the one path
     * that bypasses the streamSubscriberActive skip, so it's also the only path
     * that can pile up admin_rpc traffic while the live stream is otherwise
     * healthy. Debounce just that path: once a forced reconcile has actually
     * run, further forced calls within minForcedReconcileIntervalMs are
     * redundant — the stream is active and the prior reconcile just resynced it.
     */
    private suspend fun skipReasonFor(
        allowWhileStreamActive: Boolean,
        isForcedWhileActive: Boolean,
        connectionGeneration: Long,
    ): String? = when {
        streamSubscriberActive.value && !allowWhileStreamActive -> "streamSubscriberActive"
        isForcedWhileActive && isWithinForcedReconcileDebounceWindow(connectionGeneration) -> "forcedReconcileDebounced"
        else -> null
    }

    private suspend fun isWithinForcedReconcileDebounceWindow(connectionGeneration: Long): Boolean =
        reconcileFlightMutex.withLock {
            val sinceLastForced = lastForcedReconcileCompletedAtMsByGeneration[connectionGeneration]
                ?.let { nowMillis() - it } ?: return@withLock false
            sinceLastForced < minForcedReconcileIntervalMs
        }

    /**
     * Stamped with a FRESH clock read taken after the round trip completes,
     * not a timestamp from before it started — a reconcile slower than the
     * debounce window must still get its own full window from actual
     * completion, or the very next forced call would see an already-expired
     * window and the debounce would be a no-op for exactly the slow calls it
     * matters most for.
     */
    private suspend fun onForcedReconcileCompleted(isForcedWhileActive: Boolean, connectionGeneration: Long) {
        if (!isForcedWhileActive) return
        reconcileFlightMutex.withLock {
            lastForcedReconcileCompletedAtMsByGeneration[connectionGeneration] = nowMillis()
        }
    }

    /** Fetches the newest-window page and hands it to the write path. Returns (serverCount, appended). */
    private suspend fun fetchAndApplySnapshot(
        telemetryName: String,
        telemetryReason: String,
        connectionGeneration: Long,
    ): Pair<Int, Int>? {
        // Capture freshness before starting I/O: an invalidation while fetching
        // makes this snapshot stale rather than allowing it to overwrite newer data.
        val freshnessSequence = freshnessState.value.sequence
        val serverMessages = messageApi.listConversationMessages(
            conversationId = conversationId,
            limit = RECONCILE_LIMIT,
            order = "desc",
        ).reversed()
        if (isSupersededGeneration(connectionGeneration)) return null
        val ack = CompletableDeferred<Int>()
        eventQueue.send(
            TimelineGatewayEvent.RecentMessagesSnapshot(
                serverMessages = serverMessages,
                telemetryName = telemetryName,
                telemetryReason = telemetryReason,
                ack = ack,
                generation = connectionGeneration.takeIf { it > DEFAULT_CONNECTION_GENERATION },
                freshnessSequence = freshnessSequence,
            )
        )
        return serverMessages.size to ack.await()
    }

    private fun skipReconcile(
        timer: Telemetry.Timer,
        telemetryName: String,
        telemetryReason: String,
        reason: String,
    ): RecentMessagesReconcileOutcome {
        Telemetry.event(
            "TimelineSync", "$telemetryName.skipped",
            "conversationId" to conversationId,
            "reconcileReason" to telemetryReason,
            "reason" to reason,
        )
        timer.stop(
            "reason" to telemetryReason,
            "serverCount" to 0,
            "appended" to 0,
            "skipped" to true,
            "skipReason" to reason,
        )
        return RecentMessagesReconcileOutcome.Skipped(reason)
    }

    suspend fun applyRecentMessagesSnapshot(
        event: TimelineGatewayEvent.RecentMessagesSnapshot,
    ) {
        try {
            val applied = processor.submit(
                TimelineMutation.RecentMessagesSnapshot(
                    generation = event.generation ?: DEFAULT_CONNECTION_GENERATION,
                    freshnessSequence = event.freshnessSequence,
                    messages = event.serverMessages,
                ),
            )
            when (applied) {
                is TimelineProcessorAck.Applied -> {
                    val result = applied.result as? TimelineReductionResult.RecentMessagesApplied
                        ?: error("recent snapshot acknowledgement did not carry recent result")
                    if (result.changed) onSnapshotApplied()
                    event.ack.complete(result.appended)
                }
                is TimelineProcessorAck.Rejected -> {
                    reportStaleSnapshot(event)
                    event.ack.complete(0)
                }
                is TimelineProcessorAck.Failed -> applied.appliedResultOrThrow()
            }
        } catch (t: Throwable) {
            event.ack.completeExceptionally(t)
            throw t
        }
    }

    private fun reportStaleSnapshot(event: TimelineGatewayEvent.RecentMessagesSnapshot) {
        Telemetry.event(
            "TimelineSync", "recentReconcile.staleSnapshotDropped",
            "conversationId" to conversationId,
            "snapshotGeneration" to event.generation,
            "highestAppliedGeneration" to processor.state.value.highestAppliedReconcileGeneration,
            level = Telemetry.Level.WARN,
        )
    }

    companion object {
        private const val RECONCILE_LIMIT = 250
        private const val DEFAULT_CONNECTION_GENERATION = 0L
        const val DEFAULT_MIN_FORCED_RECONCILE_INTERVAL_MS = 4_000L
        const val DEFAULT_FRESHNESS_WINDOW_MS = 4_000L
        private val RESUME_REASONS = setOf("screen_resumed", "resumed")
    }
}
