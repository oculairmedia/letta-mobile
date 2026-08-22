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
    private var inFlightRecentReconcile: Deferred<Int>? = null
    private var lastForcedReconcileCompletedAtMs: Long? = null

    suspend fun reconcileRecentMessages(
        reason: String,
        forceRefresh: Boolean = false,
    ): Int = coroutineScope {
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
    ): Int {
        val timer = Telemetry.startTimer("TimelineSync", telemetryName)
        var appended: Int
        try {
            // A forced reconcile (post-send retries, redial recovery) is the one
            // path that bypasses the streamSubscriberActive skip, so it's also
            // the only path that can pile up admin_rpc traffic while the live
            // stream is otherwise healthy. Debounce just that path: once a
            // forced reconcile has actually run, further forced calls within
            // minForcedReconcileIntervalMs are redundant — the stream is active
            // and the prior reconcile just resynced it.
            val isForcedWhileActive = streamSubscriberActive.value && allowWhileStreamActive
            val skipReason = when {
                streamSubscriberActive.value && !allowWhileStreamActive -> "streamSubscriberActive"
                isForcedWhileActive && isWithinForcedReconcileDebounceWindow() -> "forcedReconcileDebounced"
                else -> null
            }
            if (skipReason != null) {
                return skipReconcile(timer, telemetryName, telemetryAttrs, skipReason)
            }
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
            appended = ack.await()
            // Stamped with a FRESH clock read taken after the round trip
            // completes, not the timestamp from before it started — a reconcile
            // slower than the debounce window must still get its own full
            // window from actual completion, or the very next forced call would
            // see an already-expired window and the debounce would be a no-op
            // for exactly the slow calls it matters most for.
            if (isForcedWhileActive) {
                lastForcedReconcileCompletedAtMs = nowMillis()
            }
            timer.stop(
                *telemetryAttrs,
                "serverCount" to serverMessages.size,
                "appended" to appended,
            )
            dumpTimelineState("reconcile.$telemetryName", conversationId, state.value)
            return appended
        } catch (t: Throwable) {
            timer.stopError(t, *telemetryAttrs)
            throw t
        }
    }

    private fun isWithinForcedReconcileDebounceWindow(): Boolean {
        val sinceLastForced = lastForcedReconcileCompletedAtMs?.let { nowMillis() - it } ?: return false
        return sinceLastForced < minForcedReconcileIntervalMs
    }

    private fun skipReconcile(
        timer: Telemetry.Timer,
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
        reason: String,
    ): Int {
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
        return 0
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
        const val DEFAULT_MIN_FORCED_RECONCILE_INTERVAL_MS = 4_000L
    }
}
