package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val messageApi: TimelineTransport,
    private val eventQueue: Channel<TimelineGatewayEvent>,
    private val state: MutableStateFlow<Timeline>,
    private val streamSubscriberActive: StateFlow<Boolean>,
    private val writeMutex: Mutex,
    private val applyReturnsAndResponsesFromSnapshot: (List<LettaMessage>) -> Unit,
    private val nowMillis: () -> Long = { timelineCurrentTimeMillis() },
    private val minForcedReconcileIntervalMs: Long = DEFAULT_MIN_FORCED_RECONCILE_INTERVAL_MS,
) {
    val seenRunIds = TimelineSeenRunTracker()
    private val reconcileFlightMutex = Mutex()
    private var inFlightRecentReconcile: Deferred<RecentMessagesReconcileOutcome>? = null
    private val lastForcedReconcileCompletedAtMsByGeneration = mutableMapOf<Long, Long>()

    suspend fun reconcileRecentMessages(
        reason: String,
        forceRefresh: Boolean = false,
        connectionGeneration: Long = DEFAULT_CONNECTION_GENERATION,
    ): RecentMessagesReconcileOutcome = coroutineScope {
        val shared = reconcileFlightMutex.withLock {
            inFlightRecentReconcile?.takeIf { it.isActive }?.also {
                Telemetry.event(
                    "TimelineSync", "recentReconcile.coalesced",
                    "conversationId" to conversationId,
                    "reason" to reason,
                )
            } ?: async {
                reconcileRecentMessagesFromServer(
                    telemetryName = "recentReconcile",
                    telemetryAttrs = arrayOf("reason" to reason),
                    allowWhileStreamActive = forceRefresh,
                    connectionGeneration = connectionGeneration,
                )
            }.also { inFlightRecentReconcile = it }
        }
        try {
            shared.await()
        } finally {
            reconcileFlightMutex.withLock {
                if (inFlightRecentReconcile === shared) inFlightRecentReconcile = null
            }
        }
    }

    suspend fun reconcileRecentMessagesFromServer(
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
        allowWhileStreamActive: Boolean = false,
        connectionGeneration: Long = DEFAULT_CONNECTION_GENERATION,
    ): RecentMessagesReconcileOutcome {
        val timer = Telemetry.startTimer("TimelineSync", telemetryName)
        val isForcedWhileActive = streamSubscriberActive.value && allowWhileStreamActive
        val skipReason = skipReasonFor(allowWhileStreamActive, isForcedWhileActive, connectionGeneration)
        if (skipReason != null) return skipReconcile(timer, telemetryName, telemetryAttrs, skipReason)
        return try {
            val (serverCount, appended) = fetchAndApplySnapshot(telemetryName, telemetryAttrs)
            onForcedReconcileCompleted(isForcedWhileActive, connectionGeneration)
            timer.stop(*telemetryAttrs, "serverCount" to serverCount, "appended" to appended)
            dumpTimelineState("reconcile.$telemetryName", conversationId, state.value)
            RecentMessagesReconcileOutcome.Applied(appended)
        } catch (t: Throwable) {
            timer.stopError(t, *telemetryAttrs)
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
    private fun skipReasonFor(
        allowWhileStreamActive: Boolean,
        isForcedWhileActive: Boolean,
        connectionGeneration: Long,
    ): String? = when {
        streamSubscriberActive.value && !allowWhileStreamActive -> "streamSubscriberActive"
        isForcedWhileActive && isWithinForcedReconcileDebounceWindow(connectionGeneration) -> "forcedReconcileDebounced"
        else -> null
    }

    private fun isWithinForcedReconcileDebounceWindow(connectionGeneration: Long): Boolean {
        val sinceLastForced = lastForcedReconcileCompletedAtMsByGeneration[connectionGeneration]
            ?.let { nowMillis() - it } ?: return false
        return sinceLastForced < minForcedReconcileIntervalMs
    }

    /**
     * Stamped with a FRESH clock read taken after the round trip completes,
     * not a timestamp from before it started — a reconcile slower than the
     * debounce window must still get its own full window from actual
     * completion, or the very next forced call would see an already-expired
     * window and the debounce would be a no-op for exactly the slow calls it
     * matters most for.
     */
    private fun onForcedReconcileCompleted(isForcedWhileActive: Boolean, connectionGeneration: Long) {
        if (isForcedWhileActive) {
            lastForcedReconcileCompletedAtMsByGeneration[connectionGeneration] = nowMillis()
        }
    }

    /** Fetches the newest-window page and hands it to the write path. Returns (serverCount, appended). */
    private suspend fun fetchAndApplySnapshot(
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
    ): Pair<Int, Int> {
        val serverMessages = messageApi.listConversationMessages(
            conversationId = conversationId,
            limit = RECONCILE_LIMIT,
            order = "desc",
        ).reversed()
        val ack = CompletableDeferred<Int>()
        eventQueue.send(
            TimelineGatewayEvent.RecentMessagesSnapshot(
                serverMessages = serverMessages,
                telemetryName = telemetryName,
                telemetryAttrs = telemetryAttrs.toList(),
                ack = ack,
            )
        )
        return serverMessages.size to ack.await()
    }

    private fun skipReconcile(
        timer: Telemetry.Timer,
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
        reason: String,
    ): RecentMessagesReconcileOutcome {
        Telemetry.event(
            "TimelineSync", "$telemetryName.skipped",
            "conversationId" to conversationId,
            *telemetryAttrs,
            "reason" to reason,
        )
        timer.stop(
            *telemetryAttrs,
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
            val appended = writeMutex.withLock {
                applyRecentMessagesSnapshotLocked(
                    serverMessages = event.serverMessages,
                    telemetryName = event.telemetryName,
                    telemetryAttrs = event.telemetryAttrs.toTypedArray(),
                )
            }
            event.ack.complete(appended)
        } catch (t: Throwable) {
            event.ack.completeExceptionally(t)
            throw t
        }
    }

    private fun applyRecentMessagesSnapshotLocked(
        serverMessages: List<LettaMessage>,
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
    ): Int {
        val mergeResult = state.value.mergeServerMessages(serverMessages)
        state.value = mergeResult.first
        val appended = mergeResult.second
        applyReturnsAndResponsesFromSnapshot(serverMessages)
        return appended
    }

    companion object {
        private const val RECONCILE_LIMIT = 250
        private const val DEFAULT_CONNECTION_GENERATION = 0L
        const val DEFAULT_MIN_FORCED_RECONCILE_INTERVAL_MS = 4_000L
    }
}
