package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
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
) {
    val seenRunIds = TimelineSeenRunTracker()

    suspend fun reconcileRecentMessages(
        reason: String,
        forceRefresh: Boolean = false,
    ) {
        reconcileRecentMessagesFromServer(
            telemetryName = "recentReconcile",
            telemetryAttrs = arrayOf("reason" to reason),
            allowWhileStreamActive = forceRefresh,
        )
    }

    suspend fun reconcileRecentMessagesFromServer(
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
        allowWhileStreamActive: Boolean = false,
    ) {
        val timer = Telemetry.startTimer("TimelineSync", telemetryName)
        var appended = 0
        try {
            if (streamSubscriberActive.value && !allowWhileStreamActive) {
                Telemetry.event(
                    "TimelineSync", "$telemetryName.skipped",
                    "conversationId" to conversationId,
                    *telemetryAttrs,
                    "reason" to "streamSubscriberActive",
                )
                timer.stop(
                    *telemetryAttrs,
                    "serverCount" to 0,
                    "appended" to 0,
                    "skipped" to true,
                    "skipReason" to "streamSubscriberActive",
                )
                return
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
            timer.stop(
                *telemetryAttrs,
                "serverCount" to serverMessages.size,
                "appended" to appended,
            )
            dumpTimelineState("reconcile.$telemetryName", conversationId, state.value)
        } catch (t: Throwable) {
            timer.stopError(t, *telemetryAttrs)
            throw t
        }
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
    }
}
