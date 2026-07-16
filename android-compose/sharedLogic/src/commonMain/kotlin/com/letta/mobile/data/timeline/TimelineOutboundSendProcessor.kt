package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive

/**
 * Handles serializing, sending, streaming, and post-send reconciliation of outbound messages.
 */
internal class TimelineOutboundSendProcessor(
    private val conversationId: String,
    private val messageApi: TimelineTransport,
    private val eventQueue: Channel<TimelineGatewayEvent>,
    private val writeMutex: Mutex,
    private val state: MutableStateFlow<Timeline>,
    private val events: MutableSharedFlow<TimelineSyncEvent>,
    private val pendingLocalStore: PendingLocalStore,
    private val logTag: String,
    scope: CoroutineScope,
    private val ingestStreamEvent: suspend (LettaMessage) -> Unit,
    /**
     * letta-mobile-r3i1z: invoked when an outbound send's response stream ends
     * (terminal or error). Each [ingestStreamEvent] call latches the loop's
     * external-transport flag (dual-ingest suppression: while OUR turn streams
     * through the send flow, the persistent stream subscriber must not ingest
     * the same frames twice). That latch must be RELEASED when the turn ends —
     * otherwise every later subscriber-delivered frame (e.g. another client's
     * fanned-out turn observed over Iroh) is dropped as `skippedDualIngest`
     * forever, and the desktop renders the prompt but never the reply.
     * Mirrors mobile's ChatSendCoordinator, which clears the latch at turn end.
     */
    private val onSendStreamEnded: suspend () -> Unit = {},
    /**
     * letta-mobile-dangling-tool (Codex #902 finding 1): the REST/timeline-
     * transport send path (this class) previously never signaled the
     * dangling-tool-call resolver's turn lifecycle — only the WS/iroh
     * ChatSendCoordinator path did, via TimelineRepository.turnStarted /
     * turnEnded. That left a tool_call streamed through THIS path with no
     * eventual return spinning forever, since no sweep was ever scheduled.
     * Called when the outbound send stream begins; mirrors
     * [TimelineSyncLoop.turnStarted].
     */
    private val onTurnStarted: () -> Unit = {},
    /**
     * Called when the outbound send stream ends, with [clean] reflecting
     * whether it completed without throwing. Mirrors
     * [TimelineSyncLoop.turnEnded].
     */
    private val onTurnEnded: (clean: Boolean) -> Unit = {},
) {
    val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)

    init {
        scope.launch { processSendQueue() }
    }

    suspend fun send(
        content: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val otid = newOtid()
        val sentAt = timelineNow()
        val pending = PendingSend(otid, content, attachments.toTimelinePersistentList())
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
        val postTimer = Telemetry.startTimer("TimelineSync", "send.post")
        val stream = messageApi.sendConversationMessage(conversationId, request)
        postTimer.stop("otid" to otid)

        val streamTimer = Telemetry.startTimer("TimelineSync", "send.stream")
        var eventCount = 0
        var firstEventLogged = false
        val firstEventTimer = Telemetry.startTimer("TimelineSync", "send.firstEvent")

        // letta-mobile-dangling-tool (Codex #902 finding 1): signal the same
        // turn-lifecycle hooks the WS/iroh coordinator path uses, scoped to
        // exactly this stream so a dangling tool_call streamed here gets a
        // sweep scheduled instead of spinning forever.
        onTurnStarted()
        var streamCompletedCleanly = false
        try {
            stream.collect { message ->
                eventCount++
                if (!firstEventLogged) {
                    firstEventLogged = true
                    firstEventTimer.stop("otid" to otid)
                }
                ingestStreamEvent(message)
                events.emit(TimelineSyncEvent.ServerEvent(message))
            }
            streamCompletedCleanly = true
        } finally {
            // r3i1z: the turn's send stream is over (terminal or failure) — re-arm
            // the persistent stream subscriber so externally-initiated turns
            // (fanned-out observer frames) are ingested again.
            onSendStreamEnded()
            onTurnEnded(streamCompletedCleanly)
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
        // #827 review (P2): the `after` cursor MUST be a backend message id. Over
        // Iroh the streamed row's serverId (stored in liveCursor) is now an
        // optimistic `cm-stream-*` / `cm-reason-*` id (so mobile can dedupe it
        // against the disk copy) — that is NOT a backend id and cannot be used to
        // paginate message.list. If liveCursor is a tagged optimistic id, drop the
        // cursor and do the no-cursor recent-refetch (order=desc) instead, exactly
        // as when liveCursor is null. Backend ids still page normally.
        val liveCursor = state.value.liveCursor
        val afterCursor = liveCursor?.takeUnless {
            it.startsWith("cm-") || it.startsWith("client-")
        }
        var lastError: Throwable? = null
        for (attempt in 0 until RECONCILE_RETRY_ATTEMPTS) {
            try {
                return messageApi.listConversationMessages(
                    conversationId = conversationId,
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
