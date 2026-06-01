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
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
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
import kotlinx.coroutines.CompletableDeferred
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
    private val conversationCursorStore: ConversationCursorStore = NoOpConversationCursorStore,
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

    /**
     * When true, an external transport (admin-shim WS) is delivering messages
     * for this conversation. The SSE stream subscriber suppresses message
     * ingestion while this flag is set to avoid dual-ingest duplication —
     * the two transports use different message IDs for the same logical
     * events, so serverId-based dedup misses them.
     *
     * letta-mobile-y8tvn: this flag used to auto-expire after 120s as a
     * fallback against "stuck flag if TurnDone never arrives." That fallback
     * is no longer needed and was actively harmful — it killed conversations
     * after exactly 120s of idle because the flag would expire, SSE would
     * resume ingesting, and the next WS-delivered turn would get dual-
     * ingested with different ids on each transport (dedup misses).
     *
     * The flag is now structurally owned: it is set when WS ingests a frame
     * for this conversation, and cleared only by explicit
     * [clearExternalTransportActive] calls from the send coordinator on
     * disconnect / send-error / completion. The shim's transport-exclusivity
     * contract (welcome handshake) + WS keepalive (lcp-fwo) make this safe:
     * a half-open connection is now structurally detected via WS keepalive
     * within ~40s and triggers a real disconnect → real explicit clear.
     */
    @Volatile
    private var externalTransportActive = false

    // Serialize all mutations so append/replace logic is safe under concurrency.
    private val writeMutex = Mutex()

    // Queue of incoming timeline events. This is the first Linearizing Event
    // Gateway slice: ambient resume-stream frames are submitted here and folded
    // by one worker coroutine instead of mutating state from the transport
    // callback. Locally initiated send streams remain direct for now so the
    // send -> stream -> markSent -> reconcile ordering stays unchanged.
    private val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)

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

    // letta-mobile-t0vha (oc8j Phase 2): shadow-run the
    // ConversationStateHolder alongside the imperative ingest path. Every
    // WS frame the imperative path processes is ALSO emitted into the
    // holder's frames flow; the holder maintains its own fold and exposes
    // state/events/notifications. Nothing here is authoritative yet — the
    // holder's outputs are observed in telemetry (parity counter) so a later
    // Phase 3 bead can flip the source of truth once trusted.
    // BUFFER_SIZE 64 mirrors the loop's _events buffer; tryEmit never drops
    // in normal load (production tops out at a handful of frames/s).
    //
    // letta-mobile-bmgro (oc8j Phase 3a): holderHydrationSeed is the
    // re-base channel. The holder's scan starts from whatever Timeline this
    // flow emits, restarting on each new emission. `hydrate()` writes its
    // post-reduce Timeline into this seed AFTER setting _state.value, so
    // the holder catches up to the imperative path's post-hydrate state and
    // parity telemetry (matched=true/false in streamSubscriber.foldedViaHolder)
    // becomes meaningful. No source-of-truth change yet — that's Phase 3b
    // (letta-mobile-3dl85).
    private val holderFramesIn = MutableSharedFlow<LettaMessage>(extraBufferCapacity = 64)
    private val holderHydrationSeed = MutableStateFlow(Timeline(conversationId))
    @Suppress("UnusedPrivateMember") // kept alive for parity observer below
    private val holder = com.letta.mobile.data.timeline.experimental.ConversationStateHolder(
        conversationId = conversationId,
        scope = loopScope,
        frames = holderFramesIn.asSharedFlow(),
        hydrationSeed = holderHydrationSeed,
    )

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
        loopScope.launch { processEventQueue() }
        loopScope.launch { processSendQueue() }
        loopScope.launch { runStreamSubscriber() }
    }

    fun close() {
        eventQueue.close(CancellationException("TimelineSyncLoop closed"))
        sendQueue.close(CancellationException("TimelineSyncLoop closed"))
        loopJob.cancel(CancellationException("TimelineSyncLoop closed"))
    }

    private sealed interface TimelineGatewayEvent {
        data class StreamMessage(
            val message: LettaMessage,
            val ack: CompletableDeferred<Unit>? = null,
        ) : TimelineGatewayEvent
        data class LocalSendAppend(
            val pending: PendingSend,
            val sentAt: Instant,
            val ack: CompletableDeferred<Unit>,
        ) : TimelineGatewayEvent

        data class ReconcileAfterSendSnapshot(
            val otid: String,
            val serverMessages: List<LettaMessage>,
            val ack: CompletableDeferred<ReconcileAfterSendResult>,
        ) : TimelineGatewayEvent

        data class RecentMessagesSnapshot(
            val serverMessages: List<LettaMessage>,
            val telemetryName: String,
            val telemetryAttrs: List<Pair<String, Any?>>,
            val ack: CompletableDeferred<Int>,
        ) : TimelineGatewayEvent

        data class ExternalTransportLocalAppend(
            val content: String,
            val otid: String,
            val attachments: List<MessageContentPart.Image>,
            val sentAt: Instant,
            val ack: CompletableDeferred<String>,
        ) : TimelineGatewayEvent

        data class PostHandlerCollapse(val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
        data class RetrySend(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
        data class MarkSent(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
        data class MarkFailed(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
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
    suspend fun hydrate(
        limit: Int = 50,
        recordConversationCursor: Boolean = false,
        fallbackCursorSeq: Long? = null,
    ) {
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
                    conversationId = ConversationId(conversationId),
                    limit = rawFetchLimit,
                    order = "desc",
                ).reversed()
            )
            val diskRecords = runCatching { pendingLocalStore.load(conversationId) }
                .getOrDefault(emptyList())
            val hydrateEndSeq = if (recordConversationCursor) {
                response.mapNotNull { it.seqId?.toLong() }
                    .plus(listOfNotNull(fallbackCursorSeq))
                    .maxOrNull()
            } else {
                null
            }

            val hydrated = writeMutex.withLock {
                TimelineHydrationReducer.reduce(
                    conversationId = conversationId,
                    serverMessagesChronological = response,
                    timelineBeforeFetch = timelineBeforeFetch,
                    currentTimeline = _state.value,
                    diskRecords = diskRecords,
                ).also { result ->
                    _state.value = result.timeline
                    // letta-mobile-bmgro (oc8j Phase 3a): rebase the holder
                    // holder's fold onto the same post-hydrate Timeline so
                    // parity telemetry (matched=true) becomes informative.
                    // Re-emission restarts the holder's scan with empty
                    // pendingToolReturns — matches the imperative path,
                    // whose pending map is loop-local and not reset on
                    // re-hydrate (rare; cold-start is the dominant case).
                    holderHydrationSeed.value = result.timeline
                }
            }
            if (recordConversationCursor) {
                if (hydrateEndSeq != null) {
                    conversationCursorStore.recordFrame(conversationId, hydrateEndSeq)
                }
                Telemetry.event(
                    "TimelineSync", "hydrate.cursorRepaired",
                    "conversationId" to conversationId,
                    "cursorSeq" to (hydrateEndSeq ?: -1L),
                )
            }
            _events.emit(TimelineSyncEvent.Hydrated(hydrated.visibleEventCount))
            timer.stop(
                "conversationId" to conversationId,
                "rawCount" to response.size,
                "eventCount" to hydrated.visibleEventCount,
                "cursorSeq" to (hydrateEndSeq ?: -1L),
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
        val pending = PendingSend(otid, content, attachments)
        val appendAck = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.LocalSendAppend(pending, sentAt, appendAck))
        appendAck.await()
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
        return otid
    }

    internal suspend fun appendExternalTransportLocal(
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val sentAt = Instant.now()
        val ack = CompletableDeferred<String>()
        eventQueue.send(
            TimelineGatewayEvent.ExternalTransportLocalAppend(
                content = content,
                otid = otid,
                attachments = attachments,
                sentAt = sentAt,
                ack = ack,
            )
        )
        return ack.await()
    }

    suspend fun postHandlerCollapse() {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.PostHandlerCollapse(ack))
        ack.await()
    }

    /** Retry a failed send by re-enqueueing it. */
    suspend fun retry(otid: String) {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.RetrySend(otid, ack))
        ack.await()
    }

    /** Background worker: serializes inbound transport events before mutation. */
    private suspend fun processEventQueue() {
        for (event in eventQueue) {
            try {
                when (event) {
                    is TimelineGatewayEvent.StreamMessage -> {
                        applyStreamEvent(event.message)
                        event.ack?.complete(Unit)
                    }
                    is TimelineGatewayEvent.LocalSendAppend -> applyLocalSendAppend(event)
                    is TimelineGatewayEvent.ExternalTransportLocalAppend -> applyExternalTransportLocalAppend(event)
                    is TimelineGatewayEvent.ReconcileAfterSendSnapshot -> applyReconcileAfterSendSnapshot(event)
                    is TimelineGatewayEvent.RecentMessagesSnapshot -> applyRecentMessagesSnapshot(event)
                    is TimelineGatewayEvent.PostHandlerCollapse -> event.ack.complete(Unit)
                    is TimelineGatewayEvent.RetrySend -> applyRetrySend(event)
                    is TimelineGatewayEvent.MarkSent -> applyMarkSent(event)
                    is TimelineGatewayEvent.MarkFailed -> applyMarkFailed(event)
                }
            } catch (cancelled: CancellationException) {
                completeGatewayEventExceptionally(event, cancelled)
                throw cancelled
            } catch (t: Throwable) {
                completeGatewayEventExceptionally(event, t)
                Telemetry.error(
                    "TimelineSync", "gateway.eventFailed", t,
                    "conversationId" to conversationId,
                    "event" to event::class.simpleName,
                )
            }
        }
    }

    private fun completeGatewayEventExceptionally(event: TimelineGatewayEvent, t: Throwable) {
        when (event) {
            is TimelineGatewayEvent.StreamMessage -> event.ack?.completeExceptionally(t)
            is TimelineGatewayEvent.LocalSendAppend -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.ExternalTransportLocalAppend -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.ReconcileAfterSendSnapshot -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.RecentMessagesSnapshot -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.PostHandlerCollapse -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.RetrySend -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.MarkSent -> event.ack.completeExceptionally(t)
            is TimelineGatewayEvent.MarkFailed -> event.ack.completeExceptionally(t)
        }
    }

    private suspend fun applyLocalSendAppend(event: TimelineGatewayEvent.LocalSendAppend) {
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = _state.value.nextLocalPosition(),
                otid = event.pending.otid,
                content = event.pending.content,
                role = Role.USER,
                sentAt = event.sentAt,
                deliveryState = DeliveryState.SENDING,
                attachments = event.pending.attachments,
            )
            _state.value = _state.value.append(local)
            sendQueue.send(event.pending)
        }
        _events.emit(TimelineSyncEvent.LocalAppended(event.pending.otid))
        Telemetry.event(
            "TimelineSync", "send.localAppended",
            "otid" to event.pending.otid,
            "conversationId" to conversationId,
            "contentLength" to event.pending.content.length,
        )
        event.ack.complete(Unit)
    }

    private suspend fun applyReconcileAfterSendSnapshot(
        event: TimelineGatewayEvent.ReconcileAfterSendSnapshot,
    ) {
        val result = applyReconcileAfterSendSnapshot(
            otid = event.otid,
            conversationId = conversationId,
            serverMessages = event.serverMessages,
            writeMutex = writeMutex,
            state = _state,
        )
        writeMutex.withLock {
            applyReturnsAndResponsesFromSnapshot(event.serverMessages)
        }
        event.ack.complete(result)
    }

    private suspend fun applyRecentMessagesSnapshot(
        event: TimelineGatewayEvent.RecentMessagesSnapshot,
    ) {
        val appended = writeMutex.withLock {
            applyRecentMessagesSnapshotLocked(
                serverMessages = event.serverMessages,
                telemetryName = event.telemetryName,
                telemetryAttrs = event.telemetryAttrs.toTypedArray(),
            )
        }
        event.ack.complete(appended)
    }

    private suspend fun applyExternalTransportLocalAppend(event: TimelineGatewayEvent.ExternalTransportLocalAppend) {
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = _state.value.nextLocalPosition(),
                otid = event.otid,
                content = event.content,
                role = Role.USER,
                sentAt = event.sentAt,
                deliveryState = DeliveryState.SENDING,
                attachments = event.attachments,
                source = MessageSource.LETTA_SERVER,
            )
            _state.value = _state.value.append(local)
        }
        _events.emit(TimelineSyncEvent.LocalAppended(event.otid))
        Telemetry.event(
            "TimelineSync", "send.externalTransportLocalAppended",
            "otid" to event.otid,
            "conversationId" to conversationId,
            "contentLength" to event.content.length,
        )
        event.ack.complete(event.otid)
    }

    private suspend fun applyRetrySend(event: TimelineGatewayEvent.RetrySend) {
        writeMutex.withLock {
            val existing = _state.value.findByOtid(event.otid)
            if (existing is TimelineEvent.Local && existing.deliveryState == DeliveryState.FAILED) {
                _state.value = _state.value.copy(events = _state.value.events.map {
                    if (it.otid == event.otid && it is TimelineEvent.Local) {
                        it.copy(deliveryState = DeliveryState.SENDING)
                    } else it
                })
                sendQueue.send(PendingSend(event.otid, existing.content, existing.attachments))
            }
        }
        event.ack.complete(Unit)
    }

    private suspend fun applyMarkSent(event: TimelineGatewayEvent.MarkSent) {
        writeMutex.withLock {
            _state.value = _state.value.markSent(event.otid)
        }
        event.ack.complete(Unit)
    }

    private suspend fun applyMarkFailed(event: TimelineGatewayEvent.MarkFailed) {
        writeMutex.withLock {
            _state.value = _state.value.markFailed(event.otid)
        }
        event.ack.complete(Unit)
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
                val ack = CompletableDeferred<Unit>()
                eventQueue.send(TimelineGatewayEvent.MarkFailed(pending.otid, ack))
                ack.await()
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
            includePings = true,
            includeReturnMessageTypes = DEFAULT_INCLUDE_TYPES,
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
            // letta-mobile-mge5.20: delegate to the shared ingest path. The
            // previous inline path short-circuited past tool_return attachment
            // and approval_response decided-flag handling, so locally-sent
            // tool calls kept their approve/reject UI until a hydrate ran.
            ingestStreamEvent(message)
            _events.emit(TimelineSyncEvent.ServerEvent(message))
        }

        streamTimer.stop("otid" to otid, "eventCount" to eventCount)

        // Stream completed successfully → mark our local send as SENT
        val markSentAck = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkSent(otid, markSentAck))
        markSentAck.await()

        // Now fetch & reconcile to pull in the authoritative user message record
        reconcileAfterSend(otid)
    }



    /**
     * After a send completes, fetch recent messages and swap our Local user
     * event for the server-confirmed version, and pull in any missed events.
     */
    private suspend fun reconcileAfterSend(otid: String) {
        val timer = Telemetry.startTimer("TimelineSync", "reconcile")
        try {
            val serverMessages = listMessagesWithRetry(otid).reversed()
            val result = submitReconcileAfterSendSnapshot(
                otid = otid,
                serverMessages = serverMessages,
            )
            result.confirmedServerId?.let { serverId ->
                _events.emit(TimelineSyncEvent.LocalConfirmed(otid, serverId))
            }
            if (result.shouldDeletePendingLocal) {
                runCatching { pendingLocalStore.delete(otid) }
            }
            timer.stop(
                "otid" to otid,
                "serverCount" to serverMessages.size,
                "confirmedLocal" to result.confirmedLocal,
                "appendedMissing" to result.appendedMissing,
            )
        } catch (t: Throwable) {
            timer.stopError(t, "otid" to otid)
            _events.emit(TimelineSyncEvent.ReconcileError(t.message ?: "unknown"))
        }
    }

    private suspend fun submitReconcileAfterSendSnapshot(
        otid: String,
        serverMessages: List<LettaMessage>,
    ): ReconcileAfterSendResult {
        val ack = CompletableDeferred<ReconcileAfterSendResult>()
        eventQueue.send(
            TimelineGatewayEvent.ReconcileAfterSendSnapshot(
                otid = otid,
                serverMessages = serverMessages,
                ack = ack,
            )
        )
        return ack.await()
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

    internal suspend fun reconcileRecentMessages(
        reason: String,
        forceRefresh: Boolean = false,
    ) {
        reconcileRecentMessagesFromServer(
            telemetryName = "recentReconcile",
            telemetryAttrs = arrayOf("reason" to reason),
            allowWhileStreamActive = forceRefresh,
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
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkSent(otid, ack))
        ack.await()
    }

    internal suspend fun markExternalTransportLocalFailed(otid: String) {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkFailed(otid, ack))
        ack.await()
    }

    internal suspend fun reconcileExternalTransportSend(
        agentId: String,
        externalConversationId: String,
        otid: String,
    ) {
        val timer = Telemetry.startTimer("TimelineSync", "reconcile")
        try {
            val serverMessages = listAgentMessagesWithRetry(
                agentId = agentId,
                externalConversationId = externalConversationId,
                otid = otid,
            ).reversed()
            val result = submitReconcileAfterSendSnapshot(
                otid = otid,
                serverMessages = serverMessages,
            )
            result.confirmedServerId?.let { serverId ->
                _events.emit(TimelineSyncEvent.LocalConfirmed(otid, serverId))
            }
            if (result.shouldDeletePendingLocal) {
                runCatching { pendingLocalStore.delete(otid) }
            }
            timer.stop(
                "otid" to otid,
                "serverCount" to serverMessages.size,
                "confirmedLocal" to result.confirmedLocal,
                "appendedMissing" to result.appendedMissing,
            )
        } catch (t: Throwable) {
            timer.stopError(t, "otid" to otid)
            _events.emit(TimelineSyncEvent.ReconcileError(t.message ?: "unknown"))
        }
    }

    private suspend fun reconcileRecentMessagesFromServer(
        telemetryName: String,
        telemetryAttrs: Array<Pair<String, Any?>>,
        allowWhileStreamActive: Boolean = false,
    ) {
        val timer = Telemetry.startTimer("TimelineSync", telemetryName)
        var appended = 0
        try {
            if (_streamSubscriberActive.value && !allowWhileStreamActive) {
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
                conversationId = ConversationId(conversationId),
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
        val mergeResult = _state.value.mergeServerMessages(serverMessages)
        _state.value = mergeResult.first
        val appended = mergeResult.second
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
        val afterCursor = _state.value.liveCursor
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
                    agentId = AgentId(agentId),
                    limit = RECONCILE_LIMIT,
                    order = "desc",
                    conversationId = ConversationId(externalConversationId),
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
            streamSilenceTimeoutMs = streamSilenceTimeoutMs,
            reconcileForExternalRun = ::reconcileForExternalRun,
            ingestStreamEvent = ::submitStreamEvent,
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

    internal suspend fun submitStreamEvent(message: LettaMessage) {
        // letta-mobile-y8tvn: the flag is structurally owned by the WS
        // session — set on every WS ingest, cleared only by explicit
        // clearExternalTransportActive() from the send coordinator. No
        // timer-based auto-expiry: that previously killed conversations
        // exactly 120s after the last WS frame.
        if (externalTransportActive) {
            Telemetry.event(
                "TimelineSync", "streamSubscriber.skippedDualIngest",
                "conversationId" to conversationId,
                "messageType" to message.messageType,
                "messageId" to message.id,
            )
            return
        }
        eventQueue.send(TimelineGatewayEvent.StreamMessage(message))
    }

    internal suspend fun ingestStreamEvent(message: LettaMessage) {
        externalTransportActive = true
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.StreamMessage(message, ack))
        ack.await()
    }

    internal fun clearExternalTransportActive() {
        externalTransportActive = false
    }

    private suspend fun applyStreamEvent(message: LettaMessage) {
        val notification = ingestStreamEvent(
            message = message,
            writeMutex = writeMutex,
            state = _state,
            events = _events,
            pendingToolReturnsByCallId = pendingToolReturnsByCallId,
            conversationId = conversationId,
            conversationCursorStore = conversationCursorStore,
        )
        loopScope.launch { ingestNotificationDispatcher.dispatch(notification) }
        // letta-mobile-t0vha (oc8j Phase 2): fan the same frame into the
        // Shadow holder for parity. tryEmit is non-suspending
        // and never blocks the ingest path even under heavy fold; if the
        // 64-slot buffer ever fills the parity counter goes negative,
        // which is visible in Grafana as a holderParity miss.
        val emitted = holderFramesIn.tryEmit(message)
        Telemetry.event(
            "TimelineSync", "streamSubscriber.foldedViaHolder",
            "serverId" to (message.id),
            "messageType" to message.messageType,
            "emitted" to emitted,
            "holderEventCount" to holder.state.value.events.size,
            "loopEventCount" to _state.value.events.size,
            "matched" to (holder.state.value.events.size == _state.value.events.size),
        )
    }

}
