package com.letta.mobile.data.timeline

import android.util.Log
import com.letta.mobile.core.BuildConfig
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.util.Telemetry
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.EventMessage
import com.letta.mobile.data.model.HiddenReasoningMessage
import com.letta.mobile.data.model.PingMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UnknownMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.stream.SseParser
import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
/**
 * Listener invoked when the resume-stream subscriber ingests a server event
 * that represents an inbound assistant/tool message (i.e. something the user
 * probably wants to see a notification for). Implemented in the :app module
 * so :core stays free of Android notification dependencies.
 *
 * @see com.letta.mobile.data.timeline.TimelineRepository for wiring.
 */
interface IngestedMessageListener {
    /**
     * Called on the TimelineSyncLoop's coroutine context (Dispatchers.IO),
     * under the write lock, AFTER the event has been appended to state.
     * Implementations should NOT call back into the loop. Must be fast and
     * non-throwing — any exception is swallowed and logged.
     */
    suspend fun onMessageIngested(
        conversationId: String,
        serverId: String,
        messageType: String?,
        contentPreview: String?,
    )
}

class TimelineSyncLoop(
    private val messageApi: MessageApi,
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val logTag: String = "TimelineSync",
    private val ingestedListener: IngestedMessageListener? = null,
    // mge5.24: persist Locals with image attachments so they survive app
    // restarts even though the Letta server doesn't store user_message
    // records carrying non-text content. Defaults to no-op for tests that
    // don't care about persistence.
    private val pendingLocalStore: PendingLocalStore = NoOpPendingLocalStore,
) {
    private val previewJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val _state = MutableStateFlow(Timeline(conversationId))
    val state: StateFlow<Timeline> = _state.asStateFlow()

    // Serialize all mutations so append/replace logic is safe under concurrency.
    private val writeMutex = Mutex()

    // Queue of outgoing sends. Letta API returns 409 Conflict for concurrent
    // requests on the same conversation, so we must serialize client-side.
    private val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)

    // replay=1 so late subscribers (e.g. AdminChatViewModel subscribing after
    // hydrate has already fired during getOrCreate) still receive Hydrated
    // and can clear their loading state.
    private val _events = MutableSharedFlow<TimelineSyncEvent>(replay = 1, extraBufferCapacity = 64)

    /** Signal that hydrate failed — emitted by [TimelineRepository]. */
    internal suspend fun emitHydrateFailed(message: String) {
        _events.emit(TimelineSyncEvent.HydrateFailed(message))
    }
    val events: SharedFlow<TimelineSyncEvent> = _events.asSharedFlow()

    // Track run_ids we've observed via SSE. When a new one appears it means a
    // run started that we didn't initiate (e.g. user typed in Matrix or web
    // client) — so we kick off a quick reconcile to pull the user_message
    // that started it. letta-mobile-mge5: Emmanuel reported messages he sent
    // through Matrix didn't appear in the phone timeline because the SSE
    // resume-stream subscriber only delivers reliably the assistant frames
    // for the active run, not the user message that triggered it.
    private val seenRunIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch { processSendQueue() }
        scope.launch { runStreamSubscriber() }
    }

    private data class PendingSend(
        val otid: String,
        val content: String,
        val attachments: List<MessageContentPart.Image> = emptyList(),
    )

    /**
     * Load initial history from the server.
     *
     * Replaces the current timeline entirely. Should be called once when a
     * conversation is opened.
     */
    suspend fun hydrate(limit: Int = 50) = writeMutex.withLock {
        val timer = Telemetry.startTimer("TimelineSync", "hydrate")
        try {
            // Fetch the MOST RECENT N messages (order=desc), then reverse into
            // chronological order for display. Previously this used order=asc
            // which silently returned the OLDEST 50 messages — producing the
            // "oldish state" symptom Emmanuel reported 2026-04-18. For chats
            // with long history, the screen would show ancient messages with
            // recent deltas appended on top, making the timeline look years
            // out of date. letta-mobile-mge5.
            val response = messageApi.listConversationMessages(
                conversationId = conversationId,
                limit = limit,
                order = "desc",
            ).reversed()
            val rawConverted = response.mapIndexedNotNull { idx, msg ->
                msg.toTimelineEvent(position = (idx + 1).toDouble())
            }
            // Scan the raw server messages for ApprovalResponseMessages and
            // mark matching request events as decided. These responses don't
            // produce their own bubble but need to flip the approve/reject UI
            // off. letta-mobile-mge5.15.
            val decidedIds = response.filterIsInstance<ApprovalResponseMessage>()
                .mapNotNull { it.approvalRequestId }
                .toSet()
            // Index tool returns by tool_call_id so we can (a) prove
            // approvals were granted via tool execution (letta-mobile-mge5.17)
            // and (b) attach the return body to the originating TOOL_CALL
            // event (letta-mobile-mge5.19).
            val toolReturnsByCallId: Map<String, ToolReturnMessage> =
                response.filterIsInstance<ToolReturnMessage>()
                    .mapNotNull { tr -> tr.toolCallId?.let { it to tr } }
                    .toMap()
            val returnedToolCallIds = toolReturnsByCallId.keys
            val converted = rawConverted.mapNotNull { ev ->
                when (ev.messageType) {
                    TimelineMessageType.TOOL_RETURN -> null  // never standalone
                    TimelineMessageType.TOOL_CALL -> {
                        val byResponse = ev.approvalRequestId != null && ev.approvalRequestId in decidedIds
                        val byReturn = ev.toolCalls.any { it.effectiveId in returnedToolCallIds }
                        val matchingReturn = ev.toolCalls.asSequence()
                            .mapNotNull { tc -> toolReturnsByCallId[tc.effectiveId] }
                            .firstOrNull()
                        ev.copy(
                            approvalDecided = byResponse || byReturn || ev.approvalDecided,
                            toolReturnContent = matchingReturn?.toolReturn?.funcResponse
                                ?: ev.toolReturnContent,
                            toolReturnIsError = matchingReturn?.let { it.isErr == true || it.status == "error" }
                                ?: ev.toolReturnIsError,
                        )
                    }
                    else -> ev
                }
            }
            // PRESERVE pending Local sends across hydrate. Hydrate may run
            // concurrently with user sends now that getOrCreate releases the
            // loop mutex before hydrating (letta-mobile-mge5.9). Without
            // preservation, a hydrate landing right after the user taps send
            // would wipe their just-appended SENDING bubble — Emmanuel
            // reported the symptom "it's only sending the your response,
            // it's not sending my query" 2026-04-19.
            val pendingLocals = _state.value.events.filterIsInstance<TimelineEvent.Local>()
                .filter { it.deliveryState == DeliveryState.SENDING || it.deliveryState == DeliveryState.SENT || it.deliveryState == DeliveryState.FAILED }
                .filter { local -> converted.none { c -> c.otid == local.otid } }
            // mge5.24: re-inject Locals persisted to disk that the server
            // hasn't echoed (and that aren't already present in the in-memory
            // pendingLocals list — process is fresh so usually they're not).
            // Locks down the otid space so we don't double-add when both the
            // in-memory and disk copies exist, e.g. hydrate during runtime
            // after a foreground restart of the loop.
            val knownOtids = (converted.map { it.otid } + pendingLocals.map { it.otid }).toHashSet()
            val diskLocals = runCatching { pendingLocalStore.load(conversationId) }
                .getOrDefault(emptyList())
                .filter { it.otid !in knownOtids }
                .map { rec ->
                    TimelineEvent.Local(
                        position = 0.0, // assigned below
                        otid = rec.otid,
                        content = rec.content,
                        role = Role.USER,
                        sentAt = rec.sentAt,
                        deliveryState = DeliveryState.SENT,
                        attachments = rec.attachments,
                    )
                }
            val maxServerPos = converted.lastOrNull()?.position ?: 0.0
            val allPending = pendingLocals + diskLocals
            val merged = converted + allPending.mapIndexed { idx, l ->
                l.copy(position = maxServerPos + (idx + 1).toDouble())
            }
            _state.value = Timeline(
                conversationId = conversationId,
                events = merged,
                liveCursor = converted.lastOrNull()?.serverId,
            )
            _events.emit(TimelineSyncEvent.Hydrated(converted.size))
            timer.stop(
                "conversationId" to conversationId,
                "rawCount" to response.size,
                "eventCount" to converted.size,
            )
        } catch (t: Throwable) {
            timer.stopError(t, "conversationId" to conversationId)
            throw t
        }
    }

    /**
     * Send a user message. Returns the generated otid immediately; the full
     * response (stream + reconcile) flows into [state] asynchronously.
     *
     * Atomicity: under a single lock, we both reserve the Local event's
     * position AND enqueue the send. This guarantees that timeline ordering
     * matches send ordering, even under concurrent calls.
     */
    suspend fun send(
        content: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val otid = newOtid()
        val sentAt = Instant.now()
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = _state.value.nextLocalPosition(),
                otid = otid,
                content = content,
                role = Role.USER,
                sentAt = sentAt,
                deliveryState = DeliveryState.SENDING,
                attachments = attachments,
            )
            _state.value = _state.value.append(local)
            sendQueue.send(PendingSend(otid, content, attachments))  // unlimited capacity → never suspends
        }
        // mge5.24: persist sends carrying images so the bubble survives a
        // process restart. The Letta server drops user_message records that
        // include image content, so reconcile alone can't bring them back.
        // Text-only sends rely on server reconcile and don't touch disk.
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
        _events.emit(TimelineSyncEvent.LocalAppended(otid))
        Telemetry.event(
            "TimelineSync", "send.localAppended",
            "otid" to otid,
            "conversationId" to conversationId,
            "contentLength" to content.length,
        )
        return otid
    }

    /** Retry a failed send by re-enqueueing it. */
    suspend fun retry(otid: String) = writeMutex.withLock {
        val existing = _state.value.findByOtid(otid)
        if (existing !is TimelineEvent.Local || existing.deliveryState != DeliveryState.FAILED) return@withLock
        
        _state.value = _state.value.copy(events = _state.value.events.map {
            if (it.otid == otid && it is TimelineEvent.Local) {
                it.copy(deliveryState = DeliveryState.SENDING)
            } else it
        })
        sendQueue.send(PendingSend(otid, existing.content, existing.attachments))
    }

    /** Background worker: processes one send at a time from the queue. */
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
    private suspend fun streamAndReconcile(
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ) {
        // Text-only path keeps the legacy JSON-string content; multimodal uses
        // the content-parts JsonArray accepted by Letta/OpenAI-compatible APIs.
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
            includeReturnMessageTypes = DEFAULT_INCLUDE_TYPES,
        )
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "send.requestBody otid=$otid preview=${previewRequest(request)}")
        }
        val postTimer = Telemetry.startTimer("TimelineSync", "send.post")
        val channel = messageApi.sendConversationMessage(conversationId, request)
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
            // letta-mobile-mge5.20: delegate to the shared ingest path. The
            // previous inline path short-circuited past tool_return attachment
            // and approval_response decided-flag handling, so locally-sent
            // tool calls kept their approve/reject UI until a hydrate ran.
            ingestStreamEvent(message)
            _events.emit(TimelineSyncEvent.ServerEvent(message))
        }

        streamTimer.stop("otid" to otid, "eventCount" to eventCount)

        // Stream completed successfully → mark our local send as SENT
        writeMutex.withLock {
            _state.value = _state.value.markSent(otid)
        }

        // Now fetch & reconcile to pull in the authoritative user message record
        reconcileAfterSend(otid)
    }

    private fun previewRequest(req: MessageCreateRequest): String {
        val root = previewJson.encodeToJsonElement(MessageCreateRequest.serializer(), req)
        val sanitizedRoot = root.jsonObject.let { rootObject ->
            val messages = rootObject["messages"]?.jsonArray?.map { redactMessage(it) }
            if (messages == null) {
                rootObject
            } else {
                JsonObject(rootObject + ("messages" to JsonArray(messages)))
            }
        }
        val preview = previewJson.encodeToString(JsonElement.serializer(), sanitizedRoot)
        return if (preview.length <= REQUEST_PREVIEW_MAX_CHARS) {
            preview
        } else {
            preview.take(REQUEST_PREVIEW_MAX_CHARS - 1) + "…"
        }
    }

    private fun redactMessage(element: JsonElement): JsonElement {
        val message = element.jsonObject
        val content = message["content"]
        if (content !is JsonArray) return element
        return JsonObject(message + ("content" to redactContentParts(content)))
    }

    private fun redactContentParts(content: JsonArray): JsonArray = JsonArray(
        content.map { part ->
            val partObject = part.jsonObject
            when (partObject["type"]?.jsonPrimitive?.contentOrNull) {
                // Letta's native shape: truncate base64 in `source.data`.
                "image" -> {
                    val source = partObject["source"]?.jsonObject ?: return@map part
                    if (source["type"]?.jsonPrimitive?.contentOrNull != "base64") return@map part
                    val data = source["data"]?.jsonPrimitive?.contentOrNull ?: return@map part
                    val mediaType = source["media_type"]?.jsonPrimitive?.contentOrNull ?: "?"
                    JsonObject(
                        partObject + (
                            "source" to JsonObject(
                                source + ("data" to JsonPrimitive(previewBase64(mediaType, data)))
                            )
                        )
                    )
                }
                // Legacy OpenAI-style — kept so older log captures still redact.
                "image_url" -> {
                    val imageUrl = partObject["image_url"]?.jsonObject ?: return@map part
                    val url = imageUrl["url"]?.jsonPrimitive?.contentOrNull ?: return@map part
                    if (!url.startsWith("data:")) return@map part
                    JsonObject(
                        partObject + (
                            "image_url" to JsonObject(
                                imageUrl + ("url" to JsonPrimitive(previewDataUrl(url)))
                            )
                        )
                    )
                }
                else -> part
            }
        }
    )

    private fun previewBase64(mediaType: String, base64: String): String {
        return "base64(${mediaType})=${base64.take(DATA_URL_PREVIEW_CHARS)}…[truncated, totalLen=${base64.length}]"
    }

    private fun previewDataUrl(url: String): String {
        val prefix = "data:"
        val separator = ";base64,"
        val separatorIndex = url.indexOf(separator)
        if (!url.startsWith(prefix) || separatorIndex < 0) {
            return "[unsupported data url, totalLen=${url.length}]"
        }
        val mediaType = url.substring(prefix.length, separatorIndex)
        val base64 = url.substring(separatorIndex + separator.length)
        return "data:$mediaType;base64,${base64.take(DATA_URL_PREVIEW_CHARS)}…[truncated, totalLen=${url.length}]"
    }

    /**
     * After a send completes, fetch recent messages and swap our Local user
     * event for the server-confirmed version, and pull in any missed events.
     */
    private suspend fun reconcileAfterSend(otid: String) = writeMutex.withLock {
        val timer = Telemetry.startTimer("TimelineSync", "reconcile")
        var confirmedLocal = false
        var appendedMissing = 0
        try {
            // letta-mobile-j44j: retry the GET on transient failures before
            // surfacing a user-visible error. The stream already landed the
            // assistant reply as Confirmed events (see streamAndReconcile),
            // so reconcile's job here is the lower-stakes work of swapping
            // the Local user bubble to Confirmed and picking up anything
            // the SSE missed. A network blip on that GET shouldn't leave
            // the bubble stuck in SENT forever.
            val serverMessages = listMessagesWithRetry(otid).reversed()

            // 1. Swap Local→Confirmed for our outbound message
            val myMatch = serverMessages.firstOrNull { it.otid == otid }
            if (myMatch != null) {
                val existing = _state.value.findByOtid(otid)
                if (existing is TimelineEvent.Local) {
                    val confirmed = myMatch.toTimelineEvent(position = existing.position)
                    if (confirmed != null) {
                        _state.value = _state.value.replaceLocal(otid, confirmed)
                        _events.emit(TimelineSyncEvent.LocalConfirmed(otid, myMatch.id))
                        confirmedLocal = true
                        // mge5.24: server echoed our send back, so any
                        // disk-persisted Local for this otid is now obsolete.
                        // (For text sends nothing was persisted; for images
                        // this branch will rarely fire today because the
                        // server drops them — but if/when it does, clean up.)
                        runCatching { pendingLocalStore.delete(otid) }
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
                    appendedMissing++
                }
            }

            // 3. Advance liveCursor
            serverMessages.lastOrNull()?.id?.let {
                _state.value = _state.value.copy(liveCursor = it)
            }
            timer.stop(
                "otid" to otid,
                "serverCount" to serverMessages.size,
                "confirmedLocal" to confirmedLocal,
                "appendedMissing" to appendedMissing,
            )
        } catch (t: Throwable) {
            timer.stopError(t, "otid" to otid)
            _events.emit(TimelineSyncEvent.ReconcileError(t.message ?: "unknown"))
        }
    }

    /**
     * Pull recent server messages and append any we don't already have. Used
     * when we detect a run started outside our local send path (e.g. user
     * typed in Matrix), so we can land the user_message that triggered it.
     * letta-mobile-mge5: Emmanuel reported Matrix-originated user messages
     * never appeared in the phone timeline — only the assistant reply did.
     */
    /**
     * Walk a server message snapshot and apply any approval_response +
     * tool_return hints to existing TOOL_CALL events in the timeline. Flips
     * approvalDecided=true and attaches toolReturnContent as appropriate.
     * Must be invoked inside writeMutex.
     *
     * letta-mobile-mge5.21: the server's SSE /stream endpoint often omits
     * tool_return frames even though they're stored in REST. So we apply
     * these hints explicitly after any REST snapshot (hydrate, reconcile).
     */
    private fun applyReturnsAndResponsesFromSnapshot(snapshot: List<LettaMessage>) {
        val decidedIds = snapshot.filterIsInstance<ApprovalResponseMessage>()
            .mapNotNull { it.approvalRequestId }
            .toSet()
        val returnsByCallId: Map<String, ToolReturnMessage> =
            snapshot.filterIsInstance<ToolReturnMessage>()
                .mapNotNull { r -> r.toolCallId?.let { it to r } }
                .toMap()
        if (decidedIds.isEmpty() && returnsByCallId.isEmpty()) return
        val returnedToolCallIds = returnsByCallId.keys
        val newEvents = _state.value.events.map { ev ->
            if (ev !is TimelineEvent.Confirmed || ev.messageType != TimelineMessageType.TOOL_CALL) {
                return@map ev
            }
            val matchingReturn = ev.toolCalls.asSequence()
                .mapNotNull { tc -> returnsByCallId[tc.effectiveId] }
                .firstOrNull()
            val byResponse = ev.approvalRequestId != null && ev.approvalRequestId in decidedIds
            val byReturn = ev.toolCalls.any { it.effectiveId in returnedToolCallIds }
            if (matchingReturn == null && !byResponse && !byReturn) return@map ev
            ev.copy(
                approvalDecided = byResponse || byReturn || ev.approvalDecided,
                toolReturnContent = matchingReturn?.toolReturn?.funcResponse
                    ?: ev.toolReturnContent,
                toolReturnIsError = matchingReturn?.let { it.isErr == true || it.status == "error" }
                    ?: ev.toolReturnIsError,
            )
        }
        if (newEvents !== _state.value.events) {
            _state.value = _state.value.copy(events = newEvents)
        }
    }

    private suspend fun reconcileForExternalRun(runId: String) = writeMutex.withLock {
        val timer = Telemetry.startTimer("TimelineSync", "externalRunReconcile")
        var appended = 0
        try {
            val serverMessages = messageApi.listConversationMessages(
                conversationId = conversationId,
                limit = RECONCILE_LIMIT,
                order = "desc",
            ).reversed()
            serverMessages.forEach { msg ->
                val msgOtid = msg.otid ?: return@forEach
                val byOtid = _state.value.findByOtid(msgOtid)
                val byServerId = _state.value.findByServerId(msg.id)
                if (byOtid == null && byServerId == null) {
                    // Never append a standalone TOOL_RETURN event — they
                    // attach to their TOOL_CALL below. letta-mobile-mge5.21.
                    val pos = _state.value.nextLocalPosition()
                    val confirmed = msg.toTimelineEvent(position = pos) ?: return@forEach
                    if (confirmed.messageType == TimelineMessageType.TOOL_RETURN) return@forEach
                    _state.value = _state.value.append(confirmed)
                    appended++
                }
            }
            // After appending new events, apply return/response hints from
            // the full snapshot so existing TOOL_CALL bubbles pick up their
            // output + decided state. This is the key path for the UX
            // symptom "approve/reject still visible after tool ran" when the
            // server's SSE stream doesn't emit tool_return frames.
            applyReturnsAndResponsesFromSnapshot(serverMessages)
            timer.stop(
                "runId" to runId,
                "serverCount" to serverMessages.size,
                "appended" to appended,
            )
        } catch (t: Throwable) {
            timer.stopError(t, "runId" to runId)
            throw t
        }
    }

    /**
     * GET the conversation messages with bounded retry on transient failures.
     *
     * Retries: `IOException` (network blip) and `ApiException` with 5xx status
     * (server-side hiccup). A 4xx is treated as permanent and fails fast —
     * no amount of retry will rescue a bad request. Parse failures (non-IO,
     * non-ApiException throwables from deserialization) are also permanent.
     *
     * Backoff: 200ms, 400ms, 800ms (exponential). Total worst-case wait
     * before surfacing an error is ~1.4s, which is short enough that the
     * user hasn't given up yet but long enough to ride out a typical blip.
     */
    private suspend fun listMessagesWithRetry(otid: String): List<LettaMessage> {
        var lastError: Throwable? = null
        for (attempt in 0 until RECONCILE_RETRY_ATTEMPTS) {
            try {
                return messageApi.listConversationMessages(
                    conversationId = conversationId,
                    limit = RECONCILE_LIMIT,
                    order = "desc",
                )
            } catch (t: Throwable) {
                if (!isRetryableReconcileError(t) || attempt == RECONCILE_RETRY_ATTEMPTS - 1) {
                    throw t
                }
                lastError = t
                Telemetry.event(
                    "TimelineSync", "reconcile.retry",
                    "otid" to otid,
                    "attempt" to attempt + 1,
                    "errorClass" to (t::class.simpleName ?: "Unknown"),
                    "errorMessage" to (t.message ?: ""),
                )
                delay(RECONCILE_RETRY_BACKOFF_MS shl attempt)
            }
        }
        // Unreachable — the loop either returns or rethrows — but the compiler
        // doesn't know that.
        throw lastError ?: IllegalStateException("listMessagesWithRetry exhausted without error")
    }

    private fun isRetryableReconcileError(t: Throwable): Boolean = when (t) {
        is IOException -> true
        is ApiException -> t.code in 500..599
        else -> false
    }

    companion object {
        private const val DATA_URL_PREVIEW_CHARS = 32

        // Resume-stream subscriber (letta-mobile-mge5).
        private const val STREAM_BACKOFF_START_MS = 1_000L
        private const val STREAM_BACKOFF_MAX_MS = 5_000L
        private const val STREAM_DORMANT_MS = 3_000L

        private const val REQUEST_PREVIEW_MAX_CHARS = 2_048

        // Most sends produce 1-3 server messages (user echo + assistant + maybe
        // reasoning). Fetching only what we need keeps reconcile snappy.
        private const val RECONCILE_LIMIT = 10

        // letta-mobile-j44j: bounded retry on transient reconcile failures.
        // 3 attempts → ~200+400+800ms ≈ 1.4s worst-case before surfacing
        // an error to the UI. Chosen to ride out typical network blips
        // without making the user wait noticeably longer than the stream
        // itself took to complete.
        private const val RECONCILE_RETRY_ATTEMPTS = 3
        private const val RECONCILE_RETRY_BACKOFF_MS = 200L

        internal val DEFAULT_INCLUDE_TYPES = listOf(
            "assistant_message",
            "reasoning_message",
            "tool_call_message",
            "tool_return_message",
        )
    }

    // ─── Resume-stream subscriber (letta-mobile-mge5) ──────────────────────
    //
    // The Letta server's POST /v1/conversations/{id}/stream endpoint multiplexes
    // the live SSE event stream of the active run to every subscribed client.
    // This coroutine subscribes whenever there's at least one UI observer of
    // `state`, receives events, and ingests them into the timeline. It gives
    // us ambient realtime incoming messages (from any client, tick, or
    // automation) without polling.
    //
    // Semantics:
    //   - subscriptionCount == 0 (no UI observing): dormant, sleep 5s, re-check.
    //   - streamConversation() returns SSE: parse events, ingest, reset backoff.
    //   - streamConversation() throws NoActiveRunException: exponential backoff.
    //   - network error: longer backoff.

    private suspend fun runStreamSubscriber() {
        // Note: no foreground/subscriptionCount gate. The subscriber runs for
        // the full lifetime of this TimelineSyncLoop (which is a @Singleton-
        // scoped cache in TimelineRepository). Process lifetime is extended
        // via ChatPushService (a foreground service) so messages are delivered
        // even when the screen is off / app backgrounded. This is the push
        // architecture agreed on 2026-04-18 after the subscriptionCount gate
        // proved too conservative — messages never arrived when the chat
        // screen was not foregrounded.
        var backoffMs = STREAM_BACKOFF_START_MS
        var runOpenedAtMs = 0L
        var runEventsCount = 0
        while (currentCoroutineContext().isActive) {
            try {
                val channel = messageApi.streamConversation(conversationId)
                runOpenedAtMs = System.currentTimeMillis()
                runEventsCount = 0
                _events.emit(TimelineSyncEvent.StreamSubscriberOpened)
                // letta-mobile-mge5.6: per-phase telemetry for observability.
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.opened",
                    "conversationId" to conversationId,
                )
                SseParser.parse(channel).collect { message ->
                    // letta-mobile-mge5.6: raw-event counter for rate metrics.
                    runEventsCount++
                    Telemetry.event(
                        "TimelineSync", "streamSubscriber.eventReceived",
                        "conversationId" to conversationId,
                        "messageType" to (message.messageType ?: "?"),
                        "runId" to (message.runId ?: "<null>"),
                    )
                    // Detect a new run_id: this is a run we didn't start
                    // ourselves (the locally-initiated send path tracks its
                    // own otids). BLOCK on the reconcile for the first frame
                    // so the user_message that started the run lands in the
                    // timeline BEFORE the assistant frames — otherwise the
                    // user bubble appears below the reply. Subsequent frames
                    // for the same run hit seenRunIds.add()=false and skip
                    // the reconcile.  letta-mobile-mge5 for Matrix-originated
                    // messages.
                    val runId = message.runId
                    if (runId != null && seenRunIds.add(runId)) {
                        runCatching { reconcileForExternalRun(runId) }.onFailure { t ->
                            Telemetry.error(
                                "TimelineSync", "externalRunReconcile.failed", t,
                                "conversationId" to conversationId,
                                "runId" to runId,
                            )
                        }
                    }
                    ingestStreamEvent(message)
                    // letta-mobile-mge5.21: the server emits stop_reason=
                    // requires_approval then FINISHES the stream without
                    // ever sending tool_return/approval_response frames,
                    // even though these land in REST storage shortly after.
                    // Schedule a delayed reconcile to pick them up.
                    if (message is StopReason && message.reason == "requires_approval" && runId != null) {
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            runCatching { reconcileForExternalRun(runId) }.onFailure { t ->
                                Telemetry.error(
                                    "TimelineSync", "postApprovalReconcile.failed", t,
                                    "runId" to runId,
                                )
                            }
                            // And one more a little later for long-running tools.
                            kotlinx.coroutines.delay(3500)
                            runCatching { reconcileForExternalRun(runId) }
                        }
                    }
                }
                // Stream closed cleanly: run finished. Reset backoff.
                _events.emit(TimelineSyncEvent.StreamSubscriberClosed)
                // letta-mobile-mge5.6: closed-event tracking — run duration
                // and event-count let Grafana compute delivery rates and
                // detect runs that close with zero events (mechanism broken).
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.closed",
                    "conversationId" to conversationId,
                    "durationMs" to (System.currentTimeMillis() - runOpenedAtMs),
                    "eventsReceived" to runEventsCount,
                )
                backoffMs = STREAM_BACKOFF_START_MS
            } catch (e: CancellationException) {
                throw e
            } catch (_: NoActiveRunException) {
                // No active run: back off exponentially. This is the expected
                // idle path. Counted at INFO so Grafana can compute the
                // activity-density ratio (eventReceived / idle404).
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.idle404",
                    "conversationId" to conversationId,
                    "backoffMs" to backoffMs,
                )
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(STREAM_BACKOFF_MAX_MS)
            } catch (e: ApiException) {
                // The Letta server returns 400 INVALID_ARGUMENT with body
                // `{"detail":"... No active runs found ..."}` as an alternative
                // form of "no active run". It may ALSO return
                // `{"detail":"EXPIRED: Run was created more than 3 hours ago,
                // and is now expired."}` once a previously-active run ages out
                // without finishing. Both of these are the "idle" state from
                // the subscriber's point of view — back off and retry; the
                // next live run (or a subsequent probe) will open a fresh
                // stream. Everything else is an actual error.
                // letta-mobile-gqz3: before this fix, EXPIRED was mis-classified
                // as a generic error and the subscriber wedged at the backoff
                // cap, repeatedly re-attaching to the dead run forever.
                val msg = e.message ?: ""
                val isIdlePattern = msg.contains("No active runs", ignoreCase = true) ||
                    msg.contains("EXPIRED:", ignoreCase = true) ||
                    msg.contains("is now expired", ignoreCase = true)
                if (isIdlePattern) {
                    Telemetry.event(
                        "TimelineSync", "streamSubscriber.idle404",
                        "conversationId" to conversationId,
                        "backoffMs" to backoffMs,
                        "via" to "apiException",
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(STREAM_BACKOFF_MAX_MS)
                } else {
                    // letta-mobile-mge5.6: distinguish transient network /
                    // server errors from the idle path. Grafana alerts on
                    // sustained networkError rate, not on idle404.
                    Telemetry.event(
                        "TimelineSync", "streamSubscriber.networkError",
                        "conversationId" to conversationId,
                        "errorClass" to e.javaClass.simpleName,
                        "errorMessage" to (e.message ?: "<none>"),
                    )
                    Telemetry.error(
                        "TimelineSync", "streamSubscriber.error", e,
                        "conversationId" to conversationId,
                    )
                    delay(STREAM_BACKOFF_MAX_MS)
                }
            } catch (t: Throwable) {
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.networkError",
                    "conversationId" to conversationId,
                    "errorClass" to t.javaClass.simpleName,
                    "errorMessage" to (t.message ?: "<none>"),
                )
                Telemetry.error(
                    "TimelineSync", "streamSubscriber.error", t,
                    "conversationId" to conversationId,
                )
                delay(STREAM_BACKOFF_MAX_MS)
            }
        }
    }

    /**
     * Ingest a single LettaMessage received via the resume-stream SSE
     * subscription. Dedupes by server id and otid (so stream events that
     * duplicate ones we already have via reconcile are ignored). Appends
     * novel messages as Confirmed events.
     */
    internal suspend fun ingestStreamEvent(message: LettaMessage) = writeMutex.withLock {
        // letta-mobile-mge5.15: ApprovalResponseMessage doesn't produce its own
        // bubble — instead we find the corresponding ApprovalRequestMessage
        // event (by approvalRequestId) and mark it decided so the UI hides
        // the approve/reject buttons. This covers all paths: auto-approved
        // by server, approved via phone UI, approved from another client.
        if (message is ApprovalResponseMessage) {
            val reqId = message.approvalRequestId ?: return@withLock
            val match = _state.value.events.firstOrNull {
                it is TimelineEvent.Confirmed && it.approvalRequestId == reqId
            } as? TimelineEvent.Confirmed ?: return@withLock
            if (match.approvalDecided) {
                // letta-mobile-mge5.6: observed a redundant approval
                // response — already decided. Counted as a dedupe drop.
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.eventDeduped",
                    "reason" to "approvalAlreadyDecided",
                    "approvalRequestId" to reqId,
                    "conversationId" to conversationId,
                )
                return@withLock
            }
            val updated = match.copy(approvalDecided = true)
            _state.value = _state.value.replaceByServerId(updated)
            _events.emit(TimelineSyncEvent.StreamEventIngested(match.serverId, message.messageType))
            return@withLock
        }
        // Observe a tool_return_message → the tool ran → approval (if any)
        // must have been granted. Flip any matching ApprovalRequest event to
        // decided. letta-mobile-mge5.17: this is a causal fallback for when
        // the approval_response frame is dropped by SSE or has a wonky
        // discriminator. If the tool actually produced output, the request
        // was clearly approved, regardless of whether we saw the response.
        if (message is ToolReturnMessage) {
            // Attach the tool return body to the matching TOOL_CALL event
            // (letta-mobile-mge5.19). Command + output live in one bubble
            // with collapsible output in the UI. We never append a standalone
            // TOOL_RETURN event to the timeline — the return is ALWAYS a
            // continuation of a prior call.
            val tcid = message.toolCallId
            Telemetry.event(
                "TimelineSync", "toolReturn.observed",
                "toolCallId" to (tcid ?: "<null>"),
                "hasBody" to (message.toolReturn.funcResponse?.isNotEmpty() == true),
                "timelineSize" to _state.value.events.size,
            )
            if (tcid != null) {
                val match = _state.value.events.firstOrNull { ev ->
                    ev is TimelineEvent.Confirmed &&
                        ev.toolCalls.any { it.effectiveId == tcid }
                } as? TimelineEvent.Confirmed
                if (match != null) {
                    val body = message.toolReturn.funcResponse ?: ""
                    val isError = message.isErr == true || message.status == "error"
                    val updated = match.copy(
                        approvalDecided = true,  // tool ran → approved
                        toolReturnContent = body.ifEmpty { match.toolReturnContent },
                        toolReturnIsError = isError,
                    )
                    _state.value = _state.value.replaceByServerId(updated)
                    _events.emit(TimelineSyncEvent.StreamEventIngested(match.serverId, message.messageType))
                    Telemetry.event(
                        "TimelineSync", "toolReturn.attached",
                        "serverId" to match.serverId,
                        "bodyLen" to body.length,
                    )
                } else {
                    // The call bubble may not exist yet if the return arrived
                    // before the request was ingested. Log so we can see it.
                    Telemetry.event(
                        "TimelineSync", "toolReturn.noMatch",
                        "toolCallId" to tcid,
                        "timelineSize" to _state.value.events.size,
                    )
                }
            }
            return@withLock
        }
        val confirmed = message.toTimelineEvent(position = _state.value.nextLocalPosition())
            ?: return@withLock
        // Letta streams one assistant_message per step with an incrementing
        // `seq_id`, and each frame's `content` carries only the NEWLY-EMITTED
        // tokens since the last frame (not the cumulative text). The correct
        // way to render progress is to CONCATENATE deltas sharing the same
        // serverId. Use a heuristic so we don't double-concat if the server
        // ever switches to cumulative-content shape:
        //   - if incoming.content starts with existing.content -> treat as
        //     cumulative (server decided to send the full buffer), replace.
        //   - if existing.content starts with incoming.content -> older/stale
        //     frame, skip.
        //   - otherwise -> append incoming.content to existing.content.
        // letta-mobile-mge5: initial replaceByServerId-only fix produced
        // "All we got from your last message is look" (one delta's content)
        // instead of the full assistant text — reported 2026-04-18.
        val existing = _state.value.findByServerId(confirmed.serverId)
        if (existing != null) {
            val oldText = existing.content
            val newText = confirmed.content
            val mergedText = when {
                newText.isEmpty() -> oldText
                newText == oldText -> oldText
                newText.startsWith(oldText) -> newText
                oldText.startsWith(newText) -> oldText
                else -> oldText + newText
            }
            // Merge toolCalls: a later delta frame may have null/blank
            // arguments but a still-valid name/id; keep whichever list has
            // more data. Specifically, prefer the list that has more calls
            // with non-blank arguments — that's the "complete" one.
            // letta-mobile-mge5.23: fixes the "I can see the tool called
            // but can't see arguments or output" symptom where a final
            // empty-args delta was overwriting a populated earlier frame.
            val oldCalls = existing.toolCalls
            val newCalls = confirmed.toolCalls
            val oldScore = oldCalls.count { !it.arguments.isNullOrBlank() }
            val newScore = newCalls.count { !it.arguments.isNullOrBlank() }
            val mergedCalls = if (newCalls.isEmpty() && oldCalls.isNotEmpty()) oldCalls
                else if (oldCalls.isEmpty()) newCalls
                else if (newScore >= oldScore) newCalls
                else oldCalls
            val merged = confirmed.copy(
                content = mergedText,
                toolCalls = mergedCalls,
                // Preserve these fields from the existing event — a delta
                // that arrives AFTER a tool_return attachment would
                // otherwise overwrite the attached output.
                approvalDecided = existing.approvalDecided || confirmed.approvalDecided,
                toolReturnContent = confirmed.toolReturnContent ?: existing.toolReturnContent,
                toolReturnIsError = confirmed.toolReturnIsError || existing.toolReturnIsError,
                approvalRequestId = confirmed.approvalRequestId ?: existing.approvalRequestId,
            )
            _state.value = _state.value.replaceByServerId(merged)
            _state.value = _state.value.copy(liveCursor = confirmed.serverId)
            _events.emit(TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType))
            // No notification-publish for in-place updates — we already
            // published when the event first appeared.
            return@withLock
        }
        // otid-based dedupe: catches our own echoes before reconcile runs.
        if (_state.value.findByOtid(confirmed.otid) != null) {
            // letta-mobile-mge5.6: track the dedupe drop so Grafana can show
            // how many redundant frames the subscriber is absorbing.
            Telemetry.event(
                "TimelineSync", "streamSubscriber.eventDeduped",
                "reason" to "otidSeen",
                "otid" to (confirmed.otid ?: "<null>"),
                "conversationId" to conversationId,
            )
            return@withLock
        }
        _state.value = _state.value.append(confirmed)
        _state.value = _state.value.copy(liveCursor = confirmed.serverId)
        _events.emit(TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType))
        Telemetry.event(
            "TimelineSync", "streamSubscriber.ingested",
            "serverId" to confirmed.serverId,
            "messageType" to (message.messageType ?: "?"),
            "conversationId" to conversationId,
        )
        // Notify the :app module so it can post a system notification when
        // the user isn't currently viewing this conversation. We only care
        // about inbound assistant/tool_return messages — skip streams of our
        // own echo and agent-internal plumbing.
        val mt = message.messageType
        if (mt == "assistant_message" || mt == "tool_return_message") {
            try {
                ingestedListener?.onMessageIngested(
                    conversationId = conversationId,
                    serverId = confirmed.serverId,
                    messageType = mt,
                    contentPreview = confirmed.content.take(140).ifBlank { null },
                )
            } catch (t: Throwable) {
                Telemetry.error(
                    "TimelineSync", "streamSubscriber.listenerThrew", t,
                    "conversationId" to conversationId,
                )
            }
        }
    }

}

/** Observable events emitted by the sync loop for UI/log subscribers. */
sealed class TimelineSyncEvent {
    data class Hydrated(val messageCount: Int) : TimelineSyncEvent()
    data class LocalAppended(val otid: String) : TimelineSyncEvent()
    data class LocalConfirmed(val otid: String, val serverId: String) : TimelineSyncEvent()
    data class ServerEvent(val message: LettaMessage) : TimelineSyncEvent()
    data class StreamError(val type: String, val message: String) : TimelineSyncEvent()
    /** A LettaMessage was ingested via the resume-stream subscriber (letta-mobile-mge5). */
    data class StreamEventIngested(val serverId: String, val messageType: String?) : TimelineSyncEvent()
    /** Stream subscriber successfully opened a stream; resets backoff. */
    object StreamSubscriberOpened : TimelineSyncEvent()
    /** Stream subscriber closed cleanly (run finished). */
    object StreamSubscriberClosed : TimelineSyncEvent()
    data class ReconcileError(val message: String) : TimelineSyncEvent()
    data class HydrateFailed(val message: String) : TimelineSyncEvent()
}

/**
 * Convert a server [LettaMessage] to a Confirmed timeline event.
 *
 * Returns null for message types we don't display (pings, stop reasons, etc.).
 */
/**
 * Render a tool-call list for display as the Confirmed.content field.
 * Format: "name(args)" on one line per tool call. Args are the JSON-string
 * that the server sends — typically streaming concatenation results. The UI
 * bubble component parses this further for pretty-printing if desired.
 * letta-mobile-mge5: previously only the tool name was preserved, so streaming
 * argument deltas were never visible and ApprovalRequestMessages were dropped.
 */
internal fun renderToolCallContent(calls: List<com.letta.mobile.data.model.ToolCall>): String {
    if (calls.isEmpty()) return "tool_call"
    return calls.joinToString("\n") { tc ->
        val name = tc.name ?: "tool"
        val args = tc.arguments ?: ""
        if (args.isBlank()) name else "$name($args)"
    }
}

internal fun LettaMessage.toTimelineEvent(position: Double): TimelineEvent.Confirmed? {
    val (type, text) = when (this) {
        is UserMessage -> TimelineMessageType.USER to content
        is AssistantMessage -> TimelineMessageType.ASSISTANT to content
        is ReasoningMessage -> TimelineMessageType.REASONING to reasoning
        is ToolCallMessage -> TimelineMessageType.TOOL_CALL to renderToolCallContent(effectiveToolCalls)
        is ApprovalRequestMessage -> TimelineMessageType.TOOL_CALL to renderToolCallContent(effectiveToolCalls)
        is ToolReturnMessage -> TimelineMessageType.TOOL_RETURN to (toolReturn.funcResponse ?: "")
        is SystemMessage -> TimelineMessageType.SYSTEM to content
        else -> return null
    }
    val attachments = when (this) {
        is UserMessage -> this.attachments
        is AssistantMessage -> this.attachments
        is SystemMessage -> this.attachments
        is ReasoningMessage, is ToolCallMessage, is ToolReturnMessage, is ApprovalRequestMessage,
        is ApprovalResponseMessage, is HiddenReasoningMessage, is EventMessage,
        is PingMessage, is UnknownMessage, is StopReason, is UsageStatistics -> emptyList()
    }
    val effectiveOtid = otid ?: "server-$id"
    val date = runCatching { date?.let(Instant::parse) ?: Instant.now() }.getOrElse { Instant.now() }
    val toolCallsList = when (this) {
        is ToolCallMessage -> effectiveToolCalls
        is ApprovalRequestMessage -> effectiveToolCalls
        else -> emptyList()
    }
    val approvalId = when (this) {
        is ApprovalRequestMessage -> id
        else -> null
    }
    return TimelineEvent.Confirmed(
        position = position,
        otid = effectiveOtid,
        content = text,
        serverId = id,
        messageType = type,
        date = date,
        runId = runId,
        stepId = stepId,
        attachments = attachments,
        toolCalls = toolCallsList,
        approvalRequestId = approvalId,
    )
}
