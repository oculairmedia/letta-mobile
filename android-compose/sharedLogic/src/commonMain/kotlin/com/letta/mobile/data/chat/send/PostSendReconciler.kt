package com.letta.mobile.data.chat.send

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

    fun schedule(conversationId: String, otid: String) {
        val sentAtMillis = currentTimeMillis()
        scope.launch {
            for (delayMs in delaysMs()) {
                delay(delayMs.milliseconds)
                if (hasLiveIngestSince(conversationId, sentAtMillis)) {
                    Telemetry.event(
                        "AdminChatVM", "ws.postSendReconcile.skippedLiveStream",
                        "conversationId" to conversationId,
                        "otid" to otid,
                        "delayMs" to delayMs,
                    )
                    continue
                }
                runCatching {
                    timelineRepository.reconcileRecentMessages(
                        agentId = agentId,
                        conversationId = conversationId,
                        reason = "post-send-$delayMs",
                        forceRefresh = true,
                    )
                }.onSuccess {
                    Telemetry.event(
                        "AdminChatVM", "ws.postSendReconcile.ok",
                        "conversationId" to conversationId,
                        "otid" to otid,
                        "delayMs" to delayMs,
                    )
                }.onFailure { error ->
                    Telemetry.error(
                        "AdminChatVM", "ws.postSendReconcile.failed", error,
                        "conversationId" to conversationId,
                        "otid" to otid,
                        "delayMs" to delayMs,
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
