package com.letta.mobile.data.timeline

import android.util.Log
import com.letta.mobile.core.BuildConfig
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.stream.SseParser
import com.letta.mobile.util.Telemetry
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Handles serializing, sending, streaming, and post-send reconciliation of outbound messages.
 */
internal class TimelineOutboundSendProcessor(
    private val conversationId: String,
    private val messageApi: MessageApi,
    private val eventQueue: Channel<TimelineGatewayEvent>,
    private val writeMutex: Mutex,
    private val state: MutableStateFlow<Timeline>,
    private val events: MutableSharedFlow<TimelineSyncEvent>,
    private val pendingLocalStore: PendingLocalStore,
    private val logTag: String,
    scope: CoroutineScope,
    private val ingestStreamEvent: suspend (LettaMessage) -> Unit,
) {
    val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)

    private val previewJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        scope.launch { processSendQueue() }
    }

    suspend fun send(
        content: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val otid = newOtid()
        val sentAt = Instant.now()
        val pending = PendingSend(otid, content, attachments)
        val appendAck = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.LocalSendAppend(pending, sentAt, appendAck))
        appendAck.await()
        if (attachments.isNotEmpty()) {
            runCatching {
                pendingLocalStore.save(
                    PendingLocalRecord(
                        otid = otid,
                        conversationId = conversationId,
                        content = content,
                        attachments = attachments,
                        sentAt = sentAt,
                    )
                )
            }.onFailure { t ->
                Telemetry.error(logTag, "send.persistFailed", t, "otid" to otid)
            }
        }
        return otid
    }

    private suspend fun processSendQueue() {
        for (pending in sendQueue) {
            val roundtrip = Telemetry.startTimer("TimelineSync", "send.roundtrip")
            Telemetry.event(
                "TimelineSync", "send.dequeued",
                "otid" to pending.otid,
                "conversationId" to conversationId,
            )
            try {
                streamAndReconcile(pending.content, pending.otid, pending.attachments)
                roundtrip.stop("otid" to pending.otid)
            } catch (t: Throwable) {
                Telemetry.error(
                    "TimelineSync", "send.failed", t,
                    "otid" to pending.otid,
                    "conversationId" to conversationId,
                )
                val ack = CompletableDeferred<Unit>()
                eventQueue.send(TimelineGatewayEvent.MarkFailed(pending.otid, ack))
                ack.await()
                events.emit(TimelineSyncEvent.StreamError("send", t.message ?: "unknown"))
            }
        }
    }

    private suspend fun streamAndReconcile(
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ) {
        val contentElement: kotlinx.serialization.json.JsonElement = if (attachments.isEmpty()) {
            JsonPrimitive(content)
        } else {
            buildContentParts(content, attachments).toJsonArray()
        }
        val request = MessageCreateRequest(
            messages = listOf(
                kotlinx.serialization.json.Json.encodeToJsonElement(
                    MessageCreate.serializer(),
                    MessageCreate(
                        role = "user",
                        content = contentElement,
                        otid = otid,
                    )
                )
            ),
            streaming = true,
            includePings = true,
            includeReturnMessageTypes = TimelineSyncLoop.DEFAULT_INCLUDE_TYPES,
        )
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "send.requestBody otid=$otid preview=${previewRequest(request, previewJson)}")
        }
        val postTimer = Telemetry.startTimer("TimelineSync", "send.post")
        val channel = messageApi.sendConversationMessage(ConversationId(conversationId), request)
        postTimer.stop("otid" to otid)

        val streamTimer = Telemetry.startTimer("TimelineSync", "send.stream")
        var eventCount = 0
        var firstEventLogged = false
        val firstEventTimer = Telemetry.startTimer("TimelineSync", "send.firstEvent")

        SseParser.parse(channel).collect { message ->
            eventCount++
            if (!firstEventLogged) {
                firstEventLogged = true
                firstEventTimer.stop("otid" to otid)
            }
            ingestStreamEvent(message)
            events.emit(TimelineSyncEvent.ServerEvent(message))
        }

        streamTimer.stop("otid" to otid, "eventCount" to eventCount)

        val markSentAck = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkSent(otid, markSentAck))
        markSentAck.await()

        reconcileAfterSend(otid)
    }

    private suspend fun reconcileAfterSend(otid: String) {
        reconcileAfterSend(
            otid = otid,
            conversationId = conversationId,
            writeMutex = writeMutex,
            state = state,
            events = events,
            pendingLocalStore = pendingLocalStore,
            listMessagesWithRetry = ::listMessagesWithRetry
        )
    }

    private suspend fun listMessagesWithRetry(otid: String): List<LettaMessage> {
        val afterCursor = state.value.liveCursor
        var lastError: Throwable? = null
        for (attempt in 0 until RECONCILE_RETRY_ATTEMPTS) {
            try {
                return messageApi.listConversationMessages(
                    conversationId = ConversationId(conversationId),
                    limit = if (afterCursor != null) 50 else RECONCILE_LIMIT,
                    after = afterCursor,
                    order = if (afterCursor != null) "asc" else "desc",
                )
            } catch (t: Throwable) {
                if (!isRetryableReconcileError(t) || attempt == RECONCILE_RETRY_ATTEMPTS - 1) {
                    throw t
                }
                lastError = t
                Telemetry.error(
                    "TimelineSync", "reconcile.retry", t,
                    "otid" to otid,
                    "attempt" to attempt + 1,
                )
                delay(RECONCILE_RETRY_BACKOFF_MS shl attempt)
            }
        }
        throw lastError ?: IllegalStateException("listMessagesWithRetry exhausted without error")
    }

    companion object {
        private const val RECONCILE_LIMIT = 250
        private const val RECONCILE_RETRY_ATTEMPTS = 3
        private const val RECONCILE_RETRY_BACKOFF_MS = 200L
    }
}
