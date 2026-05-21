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
import com.letta.mobile.data.stream.SseFrame
import com.letta.mobile.data.stream.SseParser
import java.time.Instant
import java.util.UUID
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
     * Called on the TimelineSyncLoop's coroutine context (Dispatchers.IO)
     * after the event has been appended to state and the write lock has been
     * released. Implementations may do slow lookup/network work; exceptions
     * are swallowed and logged.
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
    private val ingestedListenerProvider: (() -> IngestedMessageListener?)? = null,
    // mge5.24: persist Locals with image attachments so they survive app
    // restarts even though the Letta server doesn't store user_message
    // records carrying non-text content. Defaults to no-op for tests that
    // don't care about persistence.
    private val pendingLocalStore: PendingLocalStore = NoOpPendingLocalStore,
    // Production default expects 25-30s server heartbeats; tests can inject a
    // shorter budget without changing the public repository API.
    private val streamSilenceTimeoutMs: Long = STREAM_SILENCE_TIMEOUT_MS,
) {
    private val loopJob = SupervisorJob(scope.coroutineContext[Job])
    private val loopScope = CoroutineScope(scope.coroutineContext + loopJob)

    private val previewJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val _state = MutableStateFlow(Timeline(conversationId))
    val state: StateFlow<Timeline> = _state.asStateFlow()

    private val _streamSubscriberActive = MutableStateFlow(false)
    internal val streamSubscriberActive: StateFlow<Boolean> = _streamSubscriberActive.asStateFlow()

    // Serialize all mutations so append/replace logic is safe under concurrency.
    private val writeMutex = Mutex()

    // Queue of outgoing sends. Letta API returns 409 Conflict for concurrent
    // requests on the same conversation, so we must serialize client-side.
    private val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)

    // replay=1 so late subscribers (e.g. AdminChatViewModel subscribing after
    // hydrate has already fired during getOrCreate) still receive Hydrated
    // and can clear their loading state.
    private val _events = MutableSharedFlow<TimelineSyncEvent>(replay = 1, extraBufferCapacity = 64)

    // Resume-stream frames are not guaranteed to arrive in causal order. In
    // practice a tool_return can beat the tool_call/approval_request frame it
    // belongs to, which used to make live output disappear until a later REST
    // reconcile reattached it. Keep unmatched returns in memory and fold them
    // into the call event as soon as that event lands.
    private val pendingToolReturnsByCallId = LinkedHashMap<String, ToolReturnMessage>()
    private val ingestNotificationDispatcher = TimelineIngestNotificationDispatcher(
        conversationId = conversationId,
        listener = ingestedListener,
        listenerProvider = ingestedListenerProvider,
    )

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
        loopScope.launch { processSendQueue() }
        loopScope.launch { runStreamSubscriber() }
    }

    fun close() {
        sendQueue.close(CancellationException("TimelineSyncLoop closed"))
        loopJob.cancel(CancellationException("TimelineSyncLoop closed"))
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
    suspend fun hydrate(limit: Int = 50) {
        val timer = Telemetry.startTimer("TimelineSync", "hydrate")
        val timelineBeforeFetch = writeMutex.withLock { _state.value }
        try {
            // Fetch the MOST RECENT N messages (order=desc), then reverse into
            // chronological order for display. Previously this used order=asc
            // which silently returned the OLDEST 50 messages — producing the
            // "oldish state" symptom Emmanuel reported 2026-04-18. For chats
            // with long history, the screen would show ancient messages with
            // recent deltas appended on top, making the timeline look years
            // out of date. letta-mobile-mge5.
            val rawFetchLimit = hydrateRawFetchLimit(limit)
            val response = normalizeHydratedMessageOrder(
                messageApi.listConversationMessages(
                    conversationId = conversationId,
                    limit = rawFetchLimit,
                    order = "desc",
                ).reversed()
            )
            val diskRecords = runCatching { pendingLocalStore.load(conversationId) }
                .getOrDefault(emptyList())

            val hydrated = writeMutex.withLock {
                TimelineHydrationReducer.reduce(
                    conversationId = conversationId,
                    serverMessagesChronological = response,
                    timelineBeforeFetch = timelineBeforeFetch,
                    currentTimeline = _state.value,
                    diskRecords = diskRecords,
                ).also { result ->
                    _state.value = result.timeline
                }
            }
            _events.emit(TimelineSyncEvent.Hydrated(hydrated.visibleEventCount))
            timer.stop(
                "conversationId" to conversationId,
                "rawCount" to response.size,
                "eventCount" to hydrated.visibleEventCount,
            )
            dumpTimelineState("hydrate", conversationId, _state.value)
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

    /**
     * letta-mobile-c87t: optimistic-append for Client Mode sends.
     *
     * Client Mode messages travel mobile → lettabot WS gateway → Letta Code SDK
     * → Letta server. The SDK does not currently accept a client-supplied otid
     * (`letta-mobile-8pyt`), so server-echoed messages won't carry our otid back
     * for the strict swap. We give Client Mode its own append path with a
     * `cm-<uuid>` local id to avoid any chance of conflating with the strict
     * otid path, and tag the event with `MessageSource.CLIENT_MODE_HARNESS` so
     * the source-scoped fuzzy matcher in the reconcile path knows it's eligible
     * for the time+content fallback. We do NOT enqueue to `sendQueue` — the
     * actual transport happens via `ClientModeChatSender` over WS, not the
     * standard REST send path. Reconcile will surface the server-echoed user
     * message as a Confirmed event with a different (server-allocated) otid;
     * the fuzzy matcher will then collapse the pair.
     *
     * Returns the synthetic local id (also used as the otid for storage in the
     * timeline so existing position/otid invariants hold). The caller must
     * NEVER pass this id to anything that expects a server-echoed otid match.
     */
    suspend fun appendClientModeLocal(
        content: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val localId = "cm-${UUID.randomUUID()}"
        val sentAt = Instant.now()
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = _state.value.nextLocalPosition(),
                otid = localId,
                content = content,
                role = Role.USER,
                sentAt = sentAt,
                // SENT, not SENDING — the WS gateway is the source of truth for
                // delivery, not this repo. We mark the bubble delivered the
                // moment we append so the UI doesn't show a spinner that the
                // gateway/SDK pipeline can't drive.
                deliveryState = DeliveryState.SENT,
                attachments = attachments,
                source = MessageSource.CLIENT_MODE_HARNESS,
            )
            _state.value = _state.value.append(local)
        }
        _events.emit(TimelineSyncEvent.LocalAppended(localId))
        Telemetry.event(
            "TimelineSync", "send.clientModeLocalAppended",
            "localId" to localId,
            "conversationId" to conversationId,
            "contentLength" to content.length,
        )
        return localId
    }

    internal suspend fun appendExternalTransportLocal(
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val sentAt = Instant.now()
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = _state.value.nextLocalPosition(),
                otid = otid,
                content = content,
                role = Role.USER,
                sentAt = sentAt,
                // letta-mobile-9hcg: mark SENDING (not SENT) while the
                // admin-shim turn is in flight. ChatTimelineObserver's
                // nextIsStreaming gate keys off a "any LETTA_SERVER Local
                // in SENDING state" check; if we mark SENT here, the
                // observer flips isStreaming back to false on every
                // assistant-delta timeline emit, the typing item
                // appears/disappears in the LazyColumn, and the chat
                // visibly flashes per chunk. The post-TurnDone path
                // (markExternalTransportLocalSent) flips us to SENT.
                deliveryState = DeliveryState.SENDING,
                attachments = attachments,
                source = MessageSource.LETTA_SERVER,
            )
            _state.value = _state.value.append(local)
        }
        _events.emit(TimelineSyncEvent.LocalAppended(otid))
        Telemetry.event(
            "TimelineSync", "send.externalTransportLocalAppended",
            "otid" to otid,
            "conversationId" to conversationId,
            "contentLength" to content.length,
        )
        return otid
    }

    /**
     * letta-mobile-5s1n: insert-or-update a Client Mode Local for assistant
     * streaming through the WS gateway. Identified by [localId] (caller
     * supplies a stable id per stream, e.g. `cm-assist-<runId>` for assistant
     * text, `cm-tool-<toolCallId>` for tool calls, `cm-reason-<uuid>` for
     * reasoning). Idempotent across repeat chunks.
     *
     * The first call appends a fresh Local; subsequent calls in-place update
     * the same event so the UI sees a single bubble grow rather than a flood
     * of new events.
     *
     * Stamps [MessageSource.CLIENT_MODE_HARNESS] and [DeliveryState.SENT]
     * (the gateway is the delivery authority). Caller is responsible for
     * choosing the appropriate [TimelineMessageType] and field shape via
     * the [build] / [transform] callbacks.
     */
    suspend fun upsertClientModeLocalAssistantChunk(
        localId: String,
        build: () -> TimelineEvent.Local,
        transform: (TimelineEvent.Local) -> TimelineEvent.Local,
    ): String {
        writeMutex.withLock {
            val before = _state.value.findByOtid(localId) is TimelineEvent.Local
            _state.value = _state.value.upsertClientModeLocal(
                otid = localId,
                transform = transform,
                build = build,
            )
            if (!before) {
                _events.emit(TimelineSyncEvent.LocalAppended(localId))
            }
        }
        return localId
    }

    /**
     * Fold a Client Mode assistant/reasoning/tool stream chunk through the
     * timeline reducer. This is the typed replacement for app-layer callers
     * supplying ad hoc build/transform lambdas.
     */
    suspend fun upsertClientModeStreamChunk(
        chunk: ClientModeStreamChunk,
        assistantMessageId: String,
        sentAt: Instant = Instant.now(),
    ): String? {
        val reduction = writeMutex.withLock {
            val reduced = _state.value.reduceClientModeStreamChunk(
                chunk = chunk,
                assistantMessageId = assistantMessageId,
                sentAt = sentAt,
            )
            _state.value = reduced.timeline
            if (reduced.appended && reduced.localId != null) {
                _events.emit(TimelineSyncEvent.LocalAppended(reduced.localId))
            }
            reduced
        }
        return reduction.localId
    }

    /**
     * letta-mobile-iuh6: Post-handler collapse — re-runs fuzzy matching
     * across all existing Confirmed events to absorb any CLIENT_MODE_HARNESS
     * Locals that were written AFTER the initial SSE reconcile already ran.
     *
     * Race condition: the notification reply handler's WS stream and the
     * SSE subscriber race to write to the timeline. When SSE wins,
     * [reconcileForExternalRun] inserts Confirmed events with no matching
     * Local to collapse into. The handler's stream then writes Locals that
     * coexist as duplicates. Re-running fuzzy collapse here absorbs them.
     *
     * Idempotent and safe to call at any time.
     */
    suspend fun postHandlerCollapse() = writeMutex.withLock {
        var collapsed = 0
        val confirmedEvents = _state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        var tl = _state.value
        for (confirmed in confirmedEvents) {
            val fuzzy = tl.collapseClientModeFuzzyMatch(confirmed)
            if (fuzzy.collapsed != null) {
                // collapseClientModeFuzzyMatch inserts a stabilized copy
                // but does NOT remove the original Confirmed that triggered
                // the match. Remove it here so only the stabilized copy remains.
                val cleaned = fuzzy.timeline.events.filter { it.otid != confirmed.otid }
                tl = fuzzy.timeline.copy(events = cleaned)
                collapsed++
                Telemetry.event(
                    "TimelineSync", "postHandlerCollapse.fuzzyCollapsed",
                    "conversationId" to conversationId,
                    "localOtid" to fuzzy.collapsed.localOtid,
                    "serverId" to fuzzy.collapsed.serverId,
                    "deltaMs" to fuzzy.collapsed.deltaMs,
                    "contentPrefix" to fuzzy.collapsed.contentPrefix,
                    "source" to fuzzy.collapsed.source.name,
                )
            }
        }
        if (collapsed > 0) {
            _state.value = tl
            Log.w(TAG, "postHandlerCollapse: collapsed $collapsed Local(s) into Confirmed events for $conversationId")
        }
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
            Log.d(logTag, "send.requestBody otid=$otid preview=${previewRequest(request, previewJson)}")
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



    /**
     * After a send completes, fetch recent messages and swap our Local user
     * event for the server-confirmed version, and pull in any missed events.
     */
    private suspend fun reconcileAfterSend(otid: String) {
        reconcileAfterSend(
            otid = otid,
            conversationId = conversationId,
            writeMutex = writeMutex,
            state = _state,
            events = _events,
            pendingLocalStore = pendingLocalStore,
            listMessagesWithRetry = ::listMessagesWithRetry,
        )
    }

    /**
     * Pull recent server messages and append any we don't already have. Used
     * when we detect a run started outside our local send path (e.g. user
     * typed in Matrix), so we can land the user_message that triggered it.
     * letta-mobile-mge5: Emmanuel reported Matrix-originated user messages
     * never appeared in the phone timeline — only the assistant reply did.
     */
    private fun applyReturnsAndResponsesFromSnapshot(snapshot: List<LettaMessage>) {
        applyReturnsAndResponsesFromSnapshot(snapshot, _state)
    }

    internal suspend fun reconcileForExternalRun(runId: String) {
        reconcileRecentMessagesFromServer(
            telemetryName = "externalRunReconcile",
            telemetryAttrs = arrayOf("runId" to runId),
        )
    }

    internal suspend fun reconcileRecentMessages(reason: String) {
        reconcileRecentMessagesFromServer(
            telemetryName = "recentReconcile",
            telemetryAttrs = arrayOf("reason" to reason),
        )
    }

    /**
     * letta-mobile-9hcg: flip an externally-tracked Local to
     * [DeliveryState.SENT]. Called from the WS coordinator on TurnDone
     * (any status), so the Local appended via
     * [appendExternalTransportLocal] doesn't sit in SENDING state past
     * the turn — which would otherwise keep
     * ChatTimelineObserver's `isStreaming` gate latched and flap the
     * typing indicator on subsequent timeline emits. Cheap — no network.
     */
    internal suspend fun markExternalTransportLocalSent(otid: String) {
        writeMutex.withLock {
            _state.value = _state.value.markSent(otid)
        }
    }

    internal suspend fun markExternalTransportLocalFailed(otid: String) {
        writeMutex.withLock {
            _state.value = _state.value.markFailed(otid)
        }
    }

    internal suspend fun reconcileExternalTransportSend(
        agentId: String,
        externalConversationId: String,
        otid: String,
    ) {
        reconcileAfterSend(
            otid = otid,
            conversationId = conversationId,
            writeMutex = writeMutex,
            state = _state,
            events = _events,
            pendingLocalStore = pendingLocalStore,
            listMessagesWithRetry = {
                listAgentMessagesWithRetry(
                    agentId = agentId,
                    externalConversationId = externalConversationId,
                    otid = otid,
                )
            },
        )
    }

    private suspend fun reconcileRecentMessagesFromServer(
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
    ) {
        val timer = Telemetry.startTimer("TimelineSync", telemetryName)
        var appended = 0
        try {
            if (_streamSubscriberActive.value) {
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
            writeMutex.withLock {
                appended = applyRecentMessagesSnapshotLocked(
                    serverMessages = serverMessages,
                    telemetryName = telemetryName,
                    telemetryAttrs = telemetryAttrs,
                )
            }
            timer.stop(
                *telemetryAttrs,
                "serverCount" to serverMessages.size,
                "appended" to appended,
            )
            dumpTimelineState("reconcile.$telemetryName", conversationId, _state.value)
        } catch (t: Throwable) {
            timer.stopError(t, *telemetryAttrs)
            throw t
        }
    }

    private fun applyRecentMessagesSnapshotLocked(
        serverMessages: List<LettaMessage>,
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
    ): Int {
        var appended = 0
        serverMessages.forEach { msg ->
            // Never append a standalone TOOL_RETURN event — they
            // attach to their TOOL_CALL below. letta-mobile-mge5.21.
            val pos = _state.value.nextLocalPosition()
            val confirmed = msg.toTimelineEvent(position = pos) ?: return@forEach
            if (confirmed.messageType == TimelineMessageType.TOOL_RETURN) return@forEach
            val byOtid = _state.value.findByOtid(confirmed.otid)
            val byServerId = _state.value.findByServerId(msg.id, confirmed.messageType)
            if (byOtid == null && byServerId == null) {
                // Fresh Client Mode runs can finish their local WS stream before the
                // Letta SSE subscriber opens. The first SSE frame then triggers this
                // reconcile, whose REST snapshot contains the same user / reasoning /
                // assistant turns that the Client Mode harness already appended locally.
                // Collapse those local harness bubbles before the generic append path,
                // otherwise the REST-confirmed copy is inserted beside the local copy
                // and later SSE deltas double-merge into it.
                val fuzzy = _state.value.collapseClientModeFuzzyMatch(confirmed)
                if (fuzzy.collapsed != null) {
                    _state.value = fuzzy.timeline
                    Telemetry.event(
                        "TimelineSync", "$telemetryName.fuzzyCollapsed",
                        "conversationId" to conversationId,
                        *telemetryAttrs,
                        "localOtid" to fuzzy.collapsed.localOtid,
                        "serverId" to fuzzy.collapsed.serverId,
                        "deltaMs" to fuzzy.collapsed.deltaMs,
                        "contentPrefix" to fuzzy.collapsed.contentPrefix,
                        "source" to fuzzy.collapsed.source.name,
                    )
                    return@forEach
                }
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
        return appended
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
                Telemetry.error(
                    "TimelineSync", "reconcile.retry", t,
                    "otid" to otid,
                    "attempt" to attempt + 1,
                )
                delay(RECONCILE_RETRY_BACKOFF_MS shl attempt)
            }
        }
        // Unreachable — the loop either returns or rethrows — but the compiler
        // doesn't know that.
        throw lastError ?: IllegalStateException("listMessagesWithRetry exhausted without error")
    }

    private suspend fun listAgentMessagesWithRetry(
        agentId: String,
        externalConversationId: String,
        otid: String,
    ): List<LettaMessage> {
        var lastError: Throwable? = null
        for (attempt in 0 until RECONCILE_RETRY_ATTEMPTS) {
            try {
                return messageApi.listMessages(
                    agentId = agentId,
                    limit = RECONCILE_LIMIT,
                    order = "desc",
                    conversationId = externalConversationId,
                )
            } catch (t: Throwable) {
                if (!isRetryableReconcileError(t) || attempt == RECONCILE_RETRY_ATTEMPTS - 1) {
                    throw t
                }
                lastError = t
                Telemetry.error(
                    "TimelineSync", "reconcile.ws.retry", t,
                    "otid" to otid,
                    "agentId" to agentId,
                    "conversationId" to externalConversationId,
                    "attempt" to attempt + 1,
                )
                delay(RECONCILE_RETRY_BACKOFF_MS shl attempt)
            }
        }
        throw lastError ?: IllegalStateException("listAgentMessagesWithRetry exhausted without error")
    }

    companion object {
        private const val TAG = "TimelineSync"

        private const val STREAM_DORMANT_MS = 3_000L
        private const val STREAM_HEARTBEAT_EXPECTED_MS = 30_000L
        private const val STREAM_SILENCE_TIMEOUT_MS = STREAM_HEARTBEAT_EXPECTED_MS * 3

        private val activeStreamCount = AtomicInteger(0)

        // Batch tool runs can persist dozens of tool_call/tool_return records
        // after a single approval. Keep reconcile wide enough to attach every
        // return to its call instead of only the most recent handful.
        private const val RECONCILE_LIMIT = 250

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
        runStreamSubscriber(
            conversationId = conversationId,
            messageApi = messageApi,
            activeStreamCount = activeStreamCount,
            events = _events,
            seenRunIds = seenRunIds,
            loopScope = loopScope,
            streamSilenceTimeoutMs = streamSilenceTimeoutMs,
            reconcileForExternalRun = ::reconcileForExternalRun,
            ingestStreamEvent = ::ingestStreamEvent,
            setStreamActive = ::setStreamSubscriberActive,
        )
    }

    private suspend fun setStreamSubscriberActive(active: Boolean) {
        if (_streamSubscriberActive.value == active) return
        _streamSubscriberActive.value = active
        Telemetry.event(
            "TimelineSync", "streamSubscriber.activeChanged",
            "conversationId" to conversationId,
            "active" to active,
        )
    }

    internal suspend fun ingestStreamEvent(message: LettaMessage) {
        ingestStreamEvent(
            message = message,
            writeMutex = writeMutex,
            state = _state,
            events = _events,
            pendingToolReturnsByCallId = pendingToolReturnsByCallId,
            conversationId = conversationId,
            ingestNotificationDispatcher = ingestNotificationDispatcher,
        )
    }

}
