package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.api.DurableAssistantBaseline
import com.letta.mobile.data.timeline.api.DurableRedialRecoveryResult
import com.letta.mobile.data.timeline.api.durableAssistantSemanticContentOrNull
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
) {
    val seenRunIds = TimelineSeenRunTracker()
    private val reconcileFlightMutex = Mutex()
    private var inFlightRecentReconcile: Deferred<Int>? = null

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

    fun captureDurableAssistantBaseline(): DurableAssistantBaseline {
        val confirmed = state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        val assistants = confirmed.filter { it.messageType == TimelineMessageType.ASSISTANT }
        return DurableAssistantBaseline(
            serverMessageIds = assistants.mapTo(mutableSetOf()) { it.serverId },
            semanticContentCounts = assistants.mapNotNull { it.content.durableAssistantSemanticContentOrNull() }
                .groupingBy { it }
                .eachCount(),
            terminalMessageIds = confirmed.asSequence()
                .filter { it.messageType == TimelineMessageType.ASSISTANT || it.messageType == TimelineMessageType.ERROR }
                .mapTo(mutableSetOf()) { it.serverId },
            capturedMessageCount = confirmed.count {
                it.messageType == TimelineMessageType.ASSISTANT || it.messageType == TimelineMessageType.ERROR
            },
        )
    }

    suspend fun reconcileRedialRecovery(
        baseline: DurableAssistantBaseline,
        reason: String,
    ): DurableRedialRecoveryResult {
        val serverMessages = messageApi.listConversationMessages(
            conversationId = conversationId,
            limit = RECONCILE_LIMIT,
            order = "desc",
        ).reversed()
        val terminalMessages = serverMessages.filter { it is AssistantMessage || it is ErrorMessage }
        val postBaseline = terminalMessages.drop(baseline.capturedMessageCount.coerceAtMost(terminalMessages.size))
        val recovery = postBaseline.asSequence()
            .filter { it.id !in baseline.terminalMessageIds }
            .mapNotNull { message ->
                when (message) {
                    is ErrorMessage -> DurableRedialRecoveryResult.Failed(message.text)
                    is AssistantMessage -> message.content.durableAssistantSemanticContentOrNull()
                        ?.let { DurableRedialRecoveryResult.Completed }
                    else -> null
                }
            }
            .firstOrNull()
            ?: DurableRedialRecoveryResult.Pending
        val ack = CompletableDeferred<Int>()
        eventQueue.send(
            TimelineGatewayEvent.RecentMessagesSnapshot(
                serverMessages = serverMessages,
                telemetryName = "redialRecovery",
                telemetryAttrs = listOf("reason" to reason),
                ack = ack,
            )
        )
        ack.await()
        return recovery
    }

    suspend fun reconcileRecentMessagesFromServer(
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
        allowWhileStreamActive: Boolean = false,
    ): Int {
        val timer = Telemetry.startTimer("TimelineSync", telemetryName)
        var appended: Int
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
                return 0
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
            return appended
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
