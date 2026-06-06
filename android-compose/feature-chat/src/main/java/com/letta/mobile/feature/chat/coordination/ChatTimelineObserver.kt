package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.letta.mobile.feature.chat.a2ui.A2uiHistoryExtractor
import com.letta.mobile.feature.chat.render.ChatMessageListChange
import com.letta.mobile.feature.chat.render.ChatUiState
import com.letta.mobile.feature.chat.render.timelineEventToUiMessage
import com.letta.mobile.feature.chat.screen.AdminChatViewModel

/**
 * Owns the long-lived timeline subscriptions and projection of timeline events
 * into [ChatUiState]. [AdminChatViewModel] still decides when to bind a
 * conversation, but this collaborator owns observer job lifecycle, hydration
 * signals, older-page prefix merging, and streaming/typing flag projection.
 */
internal class ChatTimelineObserver(
    private val scope: CoroutineScope,
    private val timelineRepository: TimelineRepository,
    private val currentConversationTracker: CurrentConversationTracker,
    private val activeReplyStreams: StateFlow<Set<String>>,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val isClientModeStreamInFlight: () -> Boolean,
    private val a2uiThinkingStartMessageCount: () -> Int?,
    private val clearA2uiThinkingOnResponse: () -> Unit,
    private val isFollowingDuplicateInitialMessageInFlight: () -> Boolean,
    private val clearFollowingDuplicateInitialMessageInFlight: () -> Unit,
    private val collapseCompletedRunsIfStreamingFinished: (previous: ChatUiState, next: ChatUiState) -> ChatUiState,
    private val syncA2uiHistorySnapshot: (conversationId: String, messages: List<A2uiMessage>) -> Map<String, A2uiSurfaceState> =
        { _, _ -> emptyMap() },
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // letta-mobile-yflpp: minimum gap between projection writes (COALESCE). Set
    // to 0 in unit tests so virtual-clock emissions stay synchronous.
    private val projectionFrameIntervalMs: Long = PROJECTION_FRAME_INTERVAL_MS,
) {
    private var observerJob: Job? = null
    private var hydrateSignalJob: Job? = null
    private var projectionCache: MutableMap<TimelineProjectionKey, CachedTimelineProjectionEvent> = mutableMapOf()
    private var lastProjectionSnapshot: CachedTimelineProjectionSnapshot? = null
    private var projectionTelemetryTick: Int = 0
    // letta-mobile-yflpp: running count of consecutive deduped no-op ticks,
    // used to throttle the uiProjection.suppressed telemetry sample.
    private var suppressedProjectionTick: Int = 0

    /**
     * Older messages fetched via pagination need to survive the next timeline
     * observer emission (which overwrites `messages` with live timeline
     * contents). Hold a per-conversation prefix and prepend it on every emit.
     */
    private var olderMessagesPrefix: Pair<String, List<UiMessage>> = "" to emptyList()

    /** Conversation id the current observer job is bound to. */
    private var observerConversationId: String? = null

    fun stop() {
        observerJob?.cancel()
        observerJob = null
        hydrateSignalJob?.cancel()
        hydrateSignalJob = null
        observerConversationId = null
        projectionCache = mutableMapOf()
        lastProjectionSnapshot = null
        projectionTelemetryTick = 0
        suppressedProjectionTick = 0
    }

    fun start(conversationId: String) {
        val convIdSame = observerConversationId == conversationId
        val jobActive = observerJob?.isActive == true
        if (convIdSame && jobActive) return

        observerJob?.cancel()
        hydrateSignalJob?.cancel()
        olderMessagesPrefix = "" to emptyList()
        projectionCache = mutableMapOf()
        lastProjectionSnapshot = null
        projectionTelemetryTick = 0
        suppressedProjectionTick = 0
        observerConversationId = conversationId
        observerJob = scope.launch {
            val flow = try {
                timelineRepository.observe(conversationId)
            } catch (e: Exception) {
                android.util.Log.e("AdminChatViewModel", "Timeline observe failed", e)
                uiState.value = uiState.value.copy(error = "Timeline init failed: ${e.message}")
                return@launch
            }

            val loop = timelineRepository.getOrCreate(conversationId)
            currentConversationTracker.setCurrent(conversationId)
            hydrateSignalJob = scope.launch {
                loop.events.collect { ev ->
                    when (ev) {
                        is TimelineSyncEvent.Hydrated -> {
                            android.util.Log.i(
                                "AdminChatViewModel",
                                "Timeline ready conv=$conversationId count=${ev.messageCount}",
                            )
                            uiState.value = uiState.value.copy(isLoadingMessages = false)
                        }
                        is TimelineSyncEvent.HydrateFailed -> {
                            uiState.value = uiState.value.copy(isLoadingMessages = false)
                        }
                        is TimelineSyncEvent.ReconcileError -> {
                            // The assistant reply may already be visible from
                            // stream Confirmed events; surface sync failure and
                            // clear stuck typing indicators.
                            val prevState = uiState.value
                            uiState.value = collapseCompletedRunsIfStreamingFinished(
                                prevState,
                                prevState.copy(
                                    error = "Couldn't sync agent reply — pull to refresh",
                                    isStreaming = false,
                                    isAgentTyping = false,
                                ),
                            )
                        }
                        else -> Unit
                    }
                }
            }

            try {
                // letta-mobile-yflpp COALESCE: during streaming the
                // authoritative Timeline StateFlow can produce ~20 updates/sec
                // (one per token delta + shadow-holder parity churn). `flow` is
                // a StateFlow, which is already conflated — a collector that
                // suspends (e.g. on the projection dispatcher or the frame-pace
                // delay below) only ever sees the LATEST value when it resumes,
                // never a backlog. Together with that pacing delay the
                // projection runs at most ~once per frame instead of once per
                // delta, so Compose hit-testing / gesture handling get a clean
                // pass and tool-card taps land mid-stream.
                flow.collect { timeline ->
                    val prefix = olderPrefixFor(conversationId)
                    val previousState = uiState.value
                    val projection = withContext(projectionDispatcher) {
                        projectTimelineSnapshot(
                            timeline = timeline,
                            prefix = prefix,
                            previousState = previousState,
                        )
                    }

                    // letta-mobile-yflpp DEDUPE: a no-op streaming tick (the
                    // tail event was re-emitted unchanged) projects to a UI
                    // byte-identical to the screen. Skip the uiState write so we
                    // don't allocate a new ChatUiState and force a recomposition
                    // storm over every tool card. Telemetry was already emitted
                    // as uiProjection.suppressed by projectTimelineSnapshot.
                    if (projection.noChange) {
                        return@collect
                    }
                    val ui = projection.ui
                    val a2uiSurfaces = syncA2uiHistorySnapshot(conversationId, projection.a2uiMessages)
                    val tailIsAssistant = projection.tailIsAssistant
                    val anyLettaServerLocalPending = projection.anyLettaServerLocalPending
                    val clearLoading = ui.isNotEmpty()
                    val newHasMoreOlder = if (projection.anyConfirmed) true else uiState.value.hasMoreOlderMessages

                    if (isFollowingDuplicateInitialMessageInFlight() && tailIsAssistant) {
                        clearFollowingDuplicateInitialMessageInFlight()
                    }
                    val duplicateInitialMessageInFlight = isFollowingDuplicateInitialMessageInFlight()
                    val prev = uiState.value
                    val isReplyStreaming = activeReplyStreams.value.contains(conversationId)
                    val streamInFlight = isClientModeStreamInFlight()
                    val a2uiStartMessageCount = a2uiThinkingStartMessageCount()
                    val a2uiResponseArrived = a2uiStartMessageCount != null && ui
                        .drop(a2uiStartMessageCount)
                        .any { it.role == "assistant" }
                    if (a2uiResponseArrived) {
                        clearA2uiThinkingOnResponse()
                    }
                    val a2uiThinkingActive = a2uiStartMessageCount != null && !a2uiResponseArrived
                    val nextIsStreaming = if (streamInFlight) prev.isStreaming
                        else if (isReplyStreaming) true
                        else if (a2uiThinkingActive) true
                        else if (duplicateInitialMessageInFlight) true
                        else anyLettaServerLocalPending
                    val nextIsAgentTyping = if (streamInFlight) prev.isAgentTyping
                        else if (isReplyStreaming) true
                        else if (a2uiThinkingActive) true
                        else if (duplicateInitialMessageInFlight) true
                        else (anyLettaServerLocalPending && !tailIsAssistant)

                    uiState.value = collapseCompletedRunsIfStreamingFinished(
                        prev,
                        prev.copy(
                            messages = ui,
                            messageListChange = projection.messageListChange,
                            a2uiSurfaces = a2uiSurfaces.toPersistentMap(),
                            isLoadingMessages = if (clearLoading) false else prev.isLoadingMessages,
                            isStreaming = nextIsStreaming,
                            isAgentTyping = nextIsAgentTyping,
                            hasMoreOlderMessages = newHasMoreOlder,
                        ),
                    )

                    // letta-mobile-yflpp COALESCE: pace real updates to at most
                    // ~one per frame. conflate() already drops backlog while we
                    // were projecting; this delay guarantees a minimum gap
                    // between writes so a burst of genuine token deltas can't
                    // peg the UI thread with >60 recompositions/sec. The latest
                    // value is always re-read after the delay, so no update is
                    // lost — they just collapse to frame cadence. A zero
                    // interval (tests) disables pacing so virtual-clock tests
                    // that drive emissions with runCurrent() stay synchronous.
                    if (projectionFrameIntervalMs > 0L) {
                        delay(projectionFrameIntervalMs)
                    }
                }
            } finally {
                hydrateSignalJob?.cancel()
            }
        }
    }

    fun mergeOlderPage(
        conversationId: String,
        olderMessages: List<UiMessage>,
        existingMessages: List<UiMessage>,
    ): List<UiMessage> {
        if (olderMessages.isEmpty()) return existingMessages

        val (prevConv, prevPrefix) = olderMessagesPrefix
        val basePrefix = if (prevConv == conversationId) prevPrefix else emptyList()
        val seenIds = HashSet<String>(basePrefix.size + olderMessages.size)
        val grown = ArrayList<UiMessage>(basePrefix.size + olderMessages.size)
        for (m in olderMessages) if (seenIds.add(m.id)) grown.add(m)
        for (m in basePrefix) if (seenIds.add(m.id)) grown.add(m)
        olderMessagesPrefix = conversationId to grown

        val existingIds = existingMessages.mapTo(mutableSetOf()) { it.id }
        return olderMessages.filterNot { it.id in existingIds } + existingMessages
    }

    private fun olderPrefixFor(conversationId: String): List<UiMessage> {
        val (prefixConv, prefixList) = olderMessagesPrefix
        return if (prefixConv == conversationId) prefixList else emptyList()
    }

    private fun combineOlderPrefix(
        prefix: List<UiMessage>,
        live: List<UiMessage>,
    ): List<UiMessage> {
        val seenIds = HashSet<String>(live.size + prefix.size)
        val combined = ArrayList<UiMessage>(live.size + prefix.size)
        for (m in prefix) if (seenIds.add(m.id)) combined.add(m)
        for (m in live) if (seenIds.add(m.id)) combined.add(m)
        return combined
    }

    private fun projectTimelineSnapshot(
        timeline: Timeline,
        prefix: List<UiMessage>,
        previousState: ChatUiState,
    ): TimelineProjection {
        val startedAtMs = System.currentTimeMillis()
        tailProjectionFastPath(timeline = timeline, prefix = prefix)?.let { fastProjection ->
            emitProjectionTelemetry(
                timeline = timeline,
                projection = fastProjection,
                prefix = prefix,
                previousState = previousState,
                startedAtMs = startedAtMs,
            )
            return fastProjection
        }

        val a2uiMessages = mutableListOf<A2uiMessage>()
        var eventsReused = 0
        var eventsProjected = 0
        val nextCache = HashMap<TimelineProjectionKey, CachedTimelineProjectionEvent>(timeline.events.size)
        val nextRecords = ArrayList<CachedTimelineProjectionEvent>(timeline.events.size)
        val live = ArrayList<UiMessage>(timeline.events.size)

        timeline.events.forEach { event ->
            val key = event.projectionKey()
            val cached = projectionCache[key]?.takeIf { it.event == event }
            val projected = if (cached != null) {
                eventsReused++
                cached
            } else {
                eventsProjected++
                event.projectForCacheRecord(key)
            }
            nextCache[key] = projected
            nextRecords += projected
            a2uiMessages += projected.a2uiMessages
            projected.uiMessage?.let(live::add)
        }
        projectionCache = nextCache

        val combined = combineOlderPrefix(prefix, live)
        val ui = combined.toImmutableList()
        val tailIsAssistant = timeline.events.lastOrNull().let {
            it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.ASSISTANT
        }
        var pendingCount = 0
        var confirmedCount = 0
        nextRecords.forEach {
            if (it.isLettaServerLocalPending) pendingCount++
            if (it.isConfirmedVisible) confirmedCount++
        }
        val anyLettaServerLocalPending = pendingCount > 0
        val anyConfirmed = confirmedCount > 0
        val liveToolCardCount = nextRecords.sumOf { it.toolCardCount }
        val prefixToolCardCount = prefix.sumOf { it.toolCardCount() }
        val result = TimelineProjection(
            ui = ui,
            tailIsAssistant = tailIsAssistant,
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = anyConfirmed,
            a2uiMessages = a2uiMessages,
            toolCardCount = prefixToolCardCount + liveToolCardCount,
            eventsReused = eventsReused,
            eventsProjected = eventsProjected,
            prefixEventsChecked = 0,
            fastPath = false,
            messageListChange = ChatMessageListChange.Full,
        )
        lastProjectionSnapshot = CachedTimelineProjectionSnapshot(
            conversationId = timeline.conversationId,
            stablePrefixVersion = timeline.stablePrefixVersion,
            records = nextRecords,
            liveMessages = live,
            prefix = prefix,
            a2uiMessages = a2uiMessages.toList(),
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = anyConfirmed,
            toolCardCount = liveToolCardCount,
            prefixToolCardCount = prefixToolCardCount,
            uiSnapshot = ui,
            tailIsAssistant = tailIsAssistant,
            pendingCount = pendingCount,
            confirmedCount = confirmedCount,
        )
        emitProjectionTelemetry(
            timeline = timeline,
            projection = result,
            prefix = prefix,
            previousState = previousState,
            startedAtMs = startedAtMs,
        )
        return result
    }

    private fun tailProjectionFastPath(
        timeline: Timeline,
        prefix: List<UiMessage>,
    ): TimelineProjection? {
        val previous = lastProjectionSnapshot ?: return null
        if (previous.conversationId != timeline.conversationId || timeline.events.isEmpty()) return null
        if (previous.prefix !== prefix) return null

        val replaceTail = timeline.events.size == previous.records.size &&
            timeline.stablePrefixVersion == previous.stablePrefixVersion
        val appendTail = timeline.events.size == previous.records.size + 1 &&
            timeline.stablePrefixVersion == previous.stablePrefixVersion + 1
        if (!replaceTail && !appendTail) return null

        val tailEvent = timeline.events.last()
        val tailKey = tailEvent.projectionKey()
        val tailCached = projectionCache[tailKey]?.takeIf { it.event == tailEvent }
        val tailRecord = tailCached ?: tailEvent.projectForCacheRecord(tailKey)

        // letta-mobile-yflpp DEDUPE: during streaming the authoritative Timeline
        // StateFlow can re-emit ~20x/sec for the SAME visible content. The
        // reducer (TimelineStreamReducer.replaceByServerId + copy(liveCursor=…))
        // produces a NEW Timeline instance even when a delta merged to identical
        // text — e.g. STALE / EQUAL / SUFFIX_DUPLICATE merge branches, or a
        // frame that only advanced a non-rendered field like seqId/liveCursor.
        // That makes the Timeline `!=` (so StateFlow emits) while the *rendered*
        // tail is byte-identical to what's already on screen. Re-projecting it
        // would allocate a fresh ChatUiState and force a full Compose
        // recomposition over every tool card, pegging the UI thread so pointer
        // hit-testing / gesture handling can't get a clean pass — the
        // intermittent dead-tap-mid-stream bug.
        //
        // We compare the *projected* tail (uiMessage + a2ui + tool-card count +
        // visible flags), NOT the raw event, so a tick that only changed a
        // non-rendered field is correctly recognised as a no-op. On a match we
        // return a no-change projection and the collector skips the uiState
        // write entirely.
        if (replaceTail) {
            val previousTailRecord = previous.records.lastOrNull()
            if (previousTailRecord != null && tailRecord.rendersSameAs(previousTailRecord)) {
                return TimelineProjection(
                    ui = previous.uiSnapshot ?: combineOlderPrefix(prefix, previous.liveMessages).toImmutableList(),
                    tailIsAssistant = previous.tailIsAssistant,
                    anyLettaServerLocalPending = previous.anyLettaServerLocalPending,
                    anyConfirmed = previous.anyConfirmed,
                    a2uiMessages = previous.a2uiMessages,
                    toolCardCount = previous.prefixToolCardCount + previous.toolCardCount,
                    eventsReused = previous.records.size,
                    eventsProjected = 0,
                    prefixEventsChecked = 0,
                    fastPath = true,
                    messageListChange = ChatMessageListChange.None,
                    noChange = true,
                )
            }
        }

        val records = if (appendTail) {
            previous.records + tailRecord
        } else {
            previous.records.dropLast(1) + tailRecord
        }
        val live = if (appendTail) {
            if (tailRecord.uiMessage == null) previous.liveMessages else previous.liveMessages + tailRecord.uiMessage
        } else {
            val previousTailHadUi = previous.records.last().uiMessage != null
            when {
                previousTailHadUi && tailRecord.uiMessage != null -> previous.liveMessages.dropLast(1) + tailRecord.uiMessage
                previousTailHadUi -> previous.liveMessages.dropLast(1)
                tailRecord.uiMessage != null -> previous.liveMessages + tailRecord.uiMessage
                else -> previous.liveMessages
            }
        }
        val a2uiMessages = if (appendTail) {
            previous.a2uiMessages + tailRecord.a2uiMessages
        } else {
            // letta-mobile-yflpp: avoid the O(history) flatMap rebuild on every
            // streaming delta. The previous snapshot already holds the combined
            // a2ui list; strip the previous tail's contribution (a suffix of
            // known length) and append the new tail's, keeping this O(delta).
            val previousTailA2uiCount = previous.records.last().a2uiMessages.size
            val prefixA2ui = if (previousTailA2uiCount == 0) {
                previous.a2uiMessages
            } else {
                previous.a2uiMessages.subList(0, previous.a2uiMessages.size - previousTailA2uiCount)
            }
            prefixA2ui + tailRecord.a2uiMessages
        }
        // letta-mobile-yflpp: track pending/confirmed as counts on the snapshot
        // so a replaceTail can recompute the aggregate booleans in O(delta)
        // (subtract the old tail, add the new tail) instead of re-scanning the
        // whole history per streaming delta.
        val previousTailRecord = previous.records.last()
        val pendingCount = when {
            appendTail -> previous.pendingCount + (if (tailRecord.isLettaServerLocalPending) 1 else 0)
            else -> previous.pendingCount -
                (if (previousTailRecord.isLettaServerLocalPending) 1 else 0) +
                (if (tailRecord.isLettaServerLocalPending) 1 else 0)
        }
        val confirmedCount = when {
            appendTail -> previous.confirmedCount + (if (tailRecord.isConfirmedVisible) 1 else 0)
            else -> previous.confirmedCount -
                (if (previousTailRecord.isConfirmedVisible) 1 else 0) +
                (if (tailRecord.isConfirmedVisible) 1 else 0)
        }
        val anyLettaServerLocalPending = pendingCount > 0
        val anyConfirmed = confirmedCount > 0
        val toolCardCount = if (appendTail) {
            previous.toolCardCount + tailRecord.toolCardCount
        } else {
            previous.toolCardCount - previous.records.last().toolCardCount + tailRecord.toolCardCount
        }
        val tailIsAssistant = tailEvent is TimelineEvent.Confirmed && tailEvent.messageType == TimelineMessageType.ASSISTANT
        val combined = combineOlderPrefix(prefix, live)
        val ui = combined.toImmutableList()

        projectionCache[tailKey] = tailRecord
        lastProjectionSnapshot = CachedTimelineProjectionSnapshot(
            conversationId = timeline.conversationId,
            stablePrefixVersion = timeline.stablePrefixVersion,
            records = records,
            liveMessages = live,
            prefix = prefix,
            a2uiMessages = a2uiMessages,
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = anyConfirmed,
            toolCardCount = toolCardCount,
            prefixToolCardCount = previous.prefixToolCardCount,
            uiSnapshot = ui,
            tailIsAssistant = tailIsAssistant,
            pendingCount = pendingCount,
            confirmedCount = confirmedCount,
        )
        val messageListChange = if (appendTail) {
            ChatMessageListChange.AppendTail
        } else {
            ChatMessageListChange.ReplaceTail
        }
        return TimelineProjection(
            ui = ui,
            tailIsAssistant = tailIsAssistant,
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = anyConfirmed,
            a2uiMessages = a2uiMessages,
            toolCardCount = previous.prefixToolCardCount + toolCardCount,
            eventsReused = records.size - (if (tailCached == null) 1 else 0),
            eventsProjected = if (tailCached == null) 1 else 0,
            prefixEventsChecked = 0,
            fastPath = true,
            messageListChange = messageListChange,
        )
    }

    private fun emitProjectionTelemetry(
        timeline: Timeline,
        projection: TimelineProjection,
        prefix: List<UiMessage>,
        previousState: ChatUiState,
        startedAtMs: Long,
    ) {
        // letta-mobile-yflpp DEDUPE telemetry: a no-op tick never reaches the
        // UI, so don't emit a full uiProjection.snapshot (which is what made
        // the storm look like ~20 real projections/sec in logcat). Emit a
        // throttled uiProjection.suppressed counter instead so the dedupe is
        // observable on-device (`adb logcat -s Telemetry/TimelineSync`) without
        // re-creating the spam it suppresses.
        if (projection.noChange) {
            suppressedProjectionTick++
            if (suppressedProjectionTick % SUPPRESSED_TELEMETRY_SAMPLE == 1) {
                Telemetry.event(
                    "TimelineSync",
                    "uiProjection.suppressed",
                    "conversationId" to timeline.conversationId,
                    "eventsTotal" to timeline.events.size,
                    "suppressedRunCount" to suppressedProjectionTick,
                    "toolCardCount" to projection.toolCardCount,
                    "isStreaming" to previousState.isStreaming,
                )
            }
            return
        }
        suppressedProjectionTick = 0

        projectionTelemetryTick++
        if (projection.fastPath && timeline.events.size > 128 && projectionTelemetryTick % 16 != 1) return

        Telemetry.event(
            "TimelineSync",
            "uiProjection.snapshot",
            "conversationId" to timeline.conversationId,
            "eventsTotal" to timeline.events.size,
            "eventsReused" to projection.eventsReused,
            "eventsProjected" to projection.eventsProjected,
            "prefixEventsChecked" to projection.prefixEventsChecked,
            "messageCount" to projection.ui.size,
            "prefixCount" to prefix.size,
            "toolCardCount" to projection.toolCardCount,
            "fastPath" to projection.fastPath,
            "isStreaming" to previousState.isStreaming,
            "isLoadingMessages" to previousState.isLoadingMessages,
            "isLoadingOlderMessages" to previousState.isLoadingOlderMessages,
            "isHydrating" to previousState.isLoadingMessages,
            // The timeline observer is downstream of reconcile emissions today;
            // no in-flight reconcile flag is exposed here yet. Keep the field
            // present so load-pressure telemetry has a stable schema.
            "isReconciling" to false,
            durationMs = System.currentTimeMillis() - startedAtMs,
        )
    }

    private fun TimelineEvent.projectForCacheRecord(key: TimelineProjectionKey): CachedTimelineProjectionEvent {
        val extractedA2uiMessages = mutableListOf<A2uiMessage>()
        val uiMessage = timelineEventToUiMessage(this)
            ?.extractA2uiHistoryInto(extractedA2uiMessages)
            ?.takeUnless { it.isEmptyAfterA2uiExtraction() }
        return CachedTimelineProjectionEvent(
            key = key,
            event = this,
            uiMessage = uiMessage,
            a2uiMessages = extractedA2uiMessages.toList(),
            toolCardCount = uiMessage?.toolCardCount() ?: 0,
            isLettaServerLocalPending = isLettaServerLocalPending(),
            isConfirmedVisible = this is TimelineEvent.Confirmed && uiMessage != null,
        )
    }

    private fun TimelineEvent.projectionKey(): TimelineProjectionKey = when (this) {
        is TimelineEvent.Confirmed -> TimelineProjectionKey("confirmed", serverId, messageType.name)
        is TimelineEvent.Local -> TimelineProjectionKey("local", otid, messageType.name)
    }

    private fun UiMessage.toolCardCount(): Int =
        toolCalls?.size ?: if (role == "tool" || generatedUi != null || approvalRequest != null || approvalResponse != null) 1 else 0

    private fun TimelineEvent.isLettaServerLocalPending(): Boolean =
        this is TimelineEvent.Local && deliveryState == DeliveryState.SENDING

    private fun UiMessage.extractA2uiHistoryInto(out: MutableList<A2uiMessage>): UiMessage {
        if (role != "assistant" || content.isBlank()) return this
        val extraction = A2uiHistoryExtractor.extract(content)
        if (extraction.messages.isEmpty()) return this
        out += extraction.messages
        return copy(content = extraction.content)
    }

    private fun UiMessage.isEmptyAfterA2uiExtraction(): Boolean =
        content.isBlank() &&
            generatedUi == null &&
            toolCalls.isNullOrEmpty() &&
            approvalRequest == null &&
            approvalResponse == null &&
            attachments.isEmpty()

    private data class TimelineProjection(
        val ui: ImmutableList<UiMessage>,
        val tailIsAssistant: Boolean,
        val anyLettaServerLocalPending: Boolean,
        val anyConfirmed: Boolean,
        val a2uiMessages: List<A2uiMessage>,
        val toolCardCount: Int,
        val eventsReused: Int,
        val eventsProjected: Int,
        val prefixEventsChecked: Int,
        val fastPath: Boolean,
        val messageListChange: ChatMessageListChange,
        /**
         * letta-mobile-yflpp: true when this projection is byte-identical to
         * the previously applied one (a no-op streaming tick). The collector
         * skips the uiState write and emits suppressed telemetry instead of a
         * full uiProjection.snapshot.
         */
        val noChange: Boolean = false,
    )

    private data class CachedTimelineProjectionSnapshot(
        val conversationId: String,
        val stablePrefixVersion: Long,
        val records: List<CachedTimelineProjectionEvent>,
        val liveMessages: List<UiMessage>,
        val prefix: List<UiMessage>,
        val a2uiMessages: List<A2uiMessage>,
        val anyLettaServerLocalPending: Boolean,
        val anyConfirmed: Boolean,
        val toolCardCount: Int,
        val prefixToolCardCount: Int,
        // letta-mobile-yflpp: cache the already-combined immutable UI list and
        // tail flag so a deduped no-op tick can return the previous projection
        // without re-running combineOlderPrefix/toImmutableList (O(history)).
        val uiSnapshot: ImmutableList<UiMessage>? = null,
        val tailIsAssistant: Boolean = false,
        // letta-mobile-yflpp: counts of pending/confirmed records so the fast
        // path can keep aggregate booleans O(delta) instead of re-scanning all
        // records per streaming tick.
        val pendingCount: Int = 0,
        val confirmedCount: Int = 0,
    )

    private data class TimelineProjectionKey(
        val source: String,
        val id: String,
        val type: String,
    )

    private data class CachedTimelineProjectionEvent(
        val key: TimelineProjectionKey,
        val event: TimelineEvent,
        val uiMessage: UiMessage?,
        val a2uiMessages: List<A2uiMessage>,
        val toolCardCount: Int,
        val isLettaServerLocalPending: Boolean,
        val isConfirmedVisible: Boolean,
    ) {
        /**
         * letta-mobile-yflpp: true when this record renders identically to
         * [other] — same projected UiMessage, same extracted a2ui messages, and
         * same projection-relevant flags. Deliberately ignores the raw
         * [TimelineEvent] (and thus non-rendered fields like seqId/liveCursor)
         * so a streaming tick that changed nothing visible is treated as a
         * no-op. UiMessage and A2uiMessage are data classes, so == is a deep
         * content compare.
         */
        fun rendersSameAs(other: CachedTimelineProjectionEvent): Boolean =
            key == other.key &&
                uiMessage == other.uiMessage &&
                toolCardCount == other.toolCardCount &&
                isLettaServerLocalPending == other.isLettaServerLocalPending &&
                isConfirmedVisible == other.isConfirmedVisible &&
                a2uiMessages == other.a2uiMessages
    }

    private companion object {
        // letta-mobile-yflpp COALESCE: minimum gap between projection writes.
        // ~one frame at 60Hz; a tight server delta stream collapses to frame
        // cadence instead of ~20 recompositions/sec, keeping the UI thread free
        // for Compose pointer hit-testing so tool-card taps land mid-stream.
        const val PROJECTION_FRAME_INTERVAL_MS = 16L

        // Sample rate for the deduped no-op telemetry counter so a long
        // suppressed run doesn't itself become spam.
        const val SUPPRESSED_TELEMETRY_SAMPLE = 32
    }
}
