package com.letta.mobile.data.timeline

import android.util.Log
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.stream.SseParser
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/**
 * Single sync loop per conversation.
 *
 * This class is the ONLY place that mutates a [Timeline]. All other code
 * observes it via [state] (read-only) or emits commands via [send].
 *
 * Responsibilities:
 * - Accept outbound sends, optimistically append Local events, enqueue for transmission
 * - Serialize sends per-conversation (the Letta server rejects concurrent sends with 409)
 * - Stream assistant/reasoning/tool responses and append Confirmed events
 * - Reconcile with GET /messages to replace Local→Confirmed once the server echoes our otid
 *
 * Anti-requirements (things it must NOT do):
 * - Must not call any sort function on the timeline (order is preserved by position)
 * - Must not use content hashing for event matching (use otid only)
 * - Must not have multiple parallel write paths (single mutex-guarded mutator)
 *
 * See `docs/architecture/poc-validation-results.md` for scenario validation.
 */
class TimelineSyncLoop(
    private val messageApi: MessageApi,
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val logTag: String = "TimelineSync",
) {
    private val _state = MutableStateFlow(Timeline(conversationId))
    val state: StateFlow<Timeline> = _state.asStateFlow()

    // Serialize all mutations so append/replace logic is safe under concurrency.
    private val writeMutex = Mutex()

    // Queue of outgoing sends. Letta API returns 409 Conflict for concurrent
    // requests on the same conversation, so we must serialize client-side.
    private val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<TimelineSyncEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TimelineSyncEvent> = _events.asSharedFlow()

    init {
        scope.launch { processSendQueue() }
    }

    private data class PendingSend(val otid: String, val content: String)

    /**
     * Load initial history from the server.
     *
     * Replaces the current timeline entirely. Should be called once when a
     * conversation is opened.
     */
    suspend fun hydrate(limit: Int = 50) = writeMutex.withLock {
        val response = messageApi.listConversationMessages(
            conversationId = conversationId,
            limit = limit,
            order = "asc",
        )
        val converted = response.mapIndexedNotNull { idx, msg ->
            msg.toTimelineEvent(position = (idx + 1).toDouble())
        }
        _state.value = Timeline(
            conversationId = conversationId,
            events = converted,
            liveCursor = converted.lastOrNull()?.serverId,
        )
        _events.emit(TimelineSyncEvent.Hydrated(converted.size))
    }

    /**
     * Send a user message. Returns the generated otid immediately; the full
     * response (stream + reconcile) flows into [state] asynchronously.
     *
     * Atomicity: under a single lock, we both reserve the Local event's
     * position AND enqueue the send. This guarantees that timeline ordering
     * matches send ordering, even under concurrent calls.
     */
    suspend fun send(content: String): String {
        val otid = newOtid()
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = _state.value.nextLocalPosition(),
                otid = otid,
                content = content,
                role = Role.USER,
                sentAt = Instant.now(),
                deliveryState = DeliveryState.SENDING,
            )
            _state.value = _state.value.append(local)
            sendQueue.send(PendingSend(otid, content))  // unlimited capacity → never suspends
        }
        _events.emit(TimelineSyncEvent.LocalAppended(otid))
        return otid
    }

    /** Retry a failed send by re-enqueueing it. */
    suspend fun retry(otid: String) {
        val existing = _state.value.findByOtid(otid)
        if (existing !is TimelineEvent.Local || existing.deliveryState != DeliveryState.FAILED) return
        writeMutex.withLock {
            _state.value = _state.value.copy(events = _state.value.events.map {
                if (it.otid == otid && it is TimelineEvent.Local) {
                    it.copy(deliveryState = DeliveryState.SENDING)
                } else it
            })
            sendQueue.send(PendingSend(otid, existing.content))
        }
    }

    /** Background worker: processes one send at a time from the queue. */
    private suspend fun processSendQueue() {
        for (pending in sendQueue) {
            val t0 = System.currentTimeMillis()
            Log.d(logTag, "processSendQueue: starting otid=${pending.otid}")
            try {
                streamAndReconcile(pending.content, pending.otid)
                Log.d(logTag, "processSendQueue: otid=${pending.otid} done in ${System.currentTimeMillis() - t0}ms")
            } catch (t: Throwable) {
                Log.e(logTag, "Send failed for otid=${pending.otid}", t)
                writeMutex.withLock {
                    _state.value = _state.value.markFailed(pending.otid)
                }
                _events.emit(TimelineSyncEvent.StreamError("send", t.message ?: "unknown"))
            }
        }
    }

    /**
     * 1. Open stream — each assistant/reasoning/tool event appends a Confirmed event.
     * 2. On stream complete, mark the Local as SENT.
     * 3. Fetch GET /messages to locate our user message (by otid) and swap Local→Confirmed.
     */
    private suspend fun streamAndReconcile(content: String, otid: String) {
        val request = MessageCreateRequest(
            messages = listOf(
                kotlinx.serialization.json.Json.encodeToJsonElement(
                    MessageCreate.serializer(),
                    MessageCreate(
                        role = "user",
                        content = JsonPrimitive(content),
                        otid = otid,
                    )
                )
            ),
            streaming = true,
            includeReturnMessageTypes = DEFAULT_INCLUDE_TYPES,
        )
        val tPost = System.currentTimeMillis()
        val channel = messageApi.sendConversationMessage(conversationId, request)
        Log.d(logTag, "POST returned in ${System.currentTimeMillis() - tPost}ms; parsing stream…")
        var eventCount = 0
        val tStream = System.currentTimeMillis()

        SseParser.parse(channel).collect { message ->
            eventCount++
            if (eventCount == 1) {
                Log.d(logTag, "first SSE event in ${System.currentTimeMillis() - tStream}ms")
            }
            writeMutex.withLock {
                val confirmed = message.toTimelineEvent(position = _state.value.nextLocalPosition())
                    ?: return@withLock
                if (_state.value.findByOtid(confirmed.otid) != null) return@withLock  // dedupe
                _state.value = _state.value.append(confirmed)
            }
            _events.emit(TimelineSyncEvent.ServerEvent(message))
        }

        Log.d(logTag, "stream complete: $eventCount events in ${System.currentTimeMillis() - tStream}ms")

        // Stream completed successfully → mark our local send as SENT
        writeMutex.withLock {
            _state.value = _state.value.markSent(otid)
        }

        // Now fetch & reconcile to pull in the authoritative user message record
        reconcileAfterSend(otid)
    }

    /**
     * After a send completes, fetch recent messages and swap our Local user
     * event for the server-confirmed version, and pull in any missed events.
     */
    private suspend fun reconcileAfterSend(otid: String) = writeMutex.withLock {
        try {
            val serverMessages = messageApi.listConversationMessages(
                conversationId = conversationId,
                limit = RECONCILE_LIMIT,
                order = "desc",
            ).reversed()

            // 1. Swap Local→Confirmed for our outbound message
            val myMatch = serverMessages.firstOrNull { it.otid == otid }
            if (myMatch != null) {
                val existing = _state.value.findByOtid(otid)
                if (existing is TimelineEvent.Local) {
                    val confirmed = myMatch.toTimelineEvent(position = existing.position)
                    if (confirmed != null) {
                        _state.value = _state.value.replaceLocal(otid, confirmed)
                        _events.emit(TimelineSyncEvent.LocalConfirmed(otid, myMatch.id))
                    }
                }
            }

            // 2. Pull in any server messages we don't yet have (missed stream events)
            serverMessages.forEach { msg ->
                val msgOtid = msg.otid ?: return@forEach
                if (_state.value.findByOtid(msgOtid) == null) {
                    val pos = _state.value.nextLocalPosition()
                    val confirmed = msg.toTimelineEvent(position = pos) ?: return@forEach
                    _state.value = _state.value.append(confirmed)
                }
            }

            // 3. Advance liveCursor
            serverMessages.lastOrNull()?.id?.let {
                _state.value = _state.value.copy(liveCursor = it)
            }
        } catch (t: Throwable) {
            Log.w(logTag, "Reconcile failed for otid=$otid", t)
            _events.emit(TimelineSyncEvent.ReconcileError(t.message ?: "unknown"))
        }
    }

    companion object {
        // Most sends produce 1-3 server messages (user echo + assistant + maybe
        // reasoning). Fetching only what we need keeps reconcile snappy.
        private const val RECONCILE_LIMIT = 10

        internal val DEFAULT_INCLUDE_TYPES = listOf(
            "assistant_message",
            "reasoning_message",
            "tool_call_message",
            "tool_return_message",
        )
    }
}

/** Observable events emitted by the sync loop for UI/log subscribers. */
sealed class TimelineSyncEvent {
    data class Hydrated(val messageCount: Int) : TimelineSyncEvent()
    data class LocalAppended(val otid: String) : TimelineSyncEvent()
    data class LocalConfirmed(val otid: String, val serverId: String) : TimelineSyncEvent()
    data class ServerEvent(val message: LettaMessage) : TimelineSyncEvent()
    data class StreamError(val type: String, val message: String) : TimelineSyncEvent()
    data class ReconcileError(val message: String) : TimelineSyncEvent()
}

/**
 * Convert a server [LettaMessage] to a Confirmed timeline event.
 *
 * Returns null for message types we don't display (pings, stop reasons, etc.).
 */
internal fun LettaMessage.toTimelineEvent(position: Double): TimelineEvent.Confirmed? {
    val (type, text) = when (this) {
        is UserMessage -> TimelineMessageType.USER to content
        is AssistantMessage -> TimelineMessageType.ASSISTANT to content
        is ReasoningMessage -> TimelineMessageType.REASONING to reasoning
        is ToolCallMessage -> TimelineMessageType.TOOL_CALL to (effectiveToolCalls.firstOrNull()?.name ?: "tool_call")
        is ToolReturnMessage -> TimelineMessageType.TOOL_RETURN to (toolReturn.funcResponse ?: "")
        is SystemMessage -> TimelineMessageType.SYSTEM to content
        else -> return null
    }
    val effectiveOtid = otid ?: "server-$id"
    val date = runCatching { date?.let(Instant::parse) ?: Instant.now() }.getOrElse { Instant.now() }
    return TimelineEvent.Confirmed(
        position = position,
        otid = effectiveOtid,
        content = text,
        serverId = id,
        messageType = type,
        date = date,
        runId = runId,
        stepId = stepId,
    )
}
