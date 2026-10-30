package com.letta.mobile.data.chat.send

import com.letta.mobile.util.attr
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Schedules defensive timeline reconciliation when no live frame follows a send. */
internal class PostSendReconciler(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val timelineRepository: TimelineExternalTransportWriter,
    private val delaysMs: () -> LongArray,
) {
    private val liveIngestLock = SynchronizedObject()
    private val lastLiveIngestByConversation = mutableMapOf<String, Long>()

    fun recordLiveIngest(conversationId: String) {
        synchronized(liveIngestLock) {
            lastLiveIngestByConversation[conversationId] = currentTimeMillis()
        }
    }

    /**
     * Reads recent history on the configured delays after [reason]. A post-send read is skipped
     * while the stream is live, since live frames carry the echo. After an expiry the read is the
     * only way to find an echo the dropped connection lost, so [skipWhileLive] is false there.
     */
    fun schedule(conversationId: String, otid: String, reason: String = "post-send", skipWhileLive: Boolean = true) {
        val sentAtMillis = currentTimeMillis()
        scope.launch {
            for (delayMs in delaysMs()) {
                delay(delayMs.milliseconds)
                if (skipWhileLive && hasLiveIngestSince(conversationId, sentAtMillis)) {
                    Telemetry.event(
                        "AdminChatVM", "ws.postSendReconcile.skippedLiveStream",
                        "conversationId" attr conversationId,
                        "otid" attr otid,
                        "delayMs" attr delayMs,
                    )
                    continue
                }
                runCatching {
                    timelineRepository.reconcileRecentMessages(
                        agentId = agentId,
                        conversationId = conversationId,
                        reason = "$reason-$delayMs",
                        forceRefresh = true,
                    )
                }.onSuccess { outcome ->
                    if (outcome is RecentMessagesReconcileOutcome.Failed) {
                        Telemetry.error(
                            "AdminChatVM", "ws.postSendReconcile.failed", outcome.cause,
                            "conversationId" attr conversationId,
                            "otid" attr otid,
                            "delayMs" attr delayMs,
                        )
                        return@onSuccess
                    }
                    Telemetry.event(
                        "AdminChatVM", "ws.postSendReconcile.ok",
                        "conversationId" attr conversationId,
                        "otid" attr otid,
                        "delayMs" attr delayMs,
                    )
                }.onFailure { error ->
                    Telemetry.error(
                        "AdminChatVM", "ws.postSendReconcile.failed", error,
                        "conversationId" attr conversationId,
                        "otid" attr otid,
                        "delayMs" attr delayMs,
                    )
                }
            }
        }
    }

    private fun hasLiveIngestSince(conversationId: String, sinceMillis: Long): Boolean =
        synchronized(liveIngestLock) {
            (lastLiveIngestByConversation[conversationId] ?: Long.MIN_VALUE) >= sinceMillis
        }

    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
