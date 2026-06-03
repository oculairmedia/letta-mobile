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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.letta.mobile.feature.chat.a2ui.A2uiHistoryExtractor
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
) {
    private var observerJob: Job? = null
    private var hydrateSignalJob: Job? = null
    private var projectionCache: Map<TimelineProjectionKey, CachedTimelineProjectionEvent> = emptyMap()

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
        projectionCache = emptyMap()
    }

    fun start(conversationId: String) {
        val convIdSame = observerConversationId == conversationId
        val jobActive = observerJob?.isActive == true
        if (convIdSame && jobActive) return

        observerJob?.cancel()
        hydrateSignalJob?.cancel()
        olderMessagesPrefix = "" to emptyList()
        projectionCache = emptyMap()
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
                            a2uiSurfaces = a2uiSurfaces.toPersistentMap(),
                            isLoadingMessages = if (clearLoading) false else prev.isLoadingMessages,
                            isStreaming = nextIsStreaming,
                            isAgentTyping = nextIsAgentTyping,
                            hasMoreOlderMessages = newHasMoreOlder,
                        ),
                    )
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
        val a2uiMessages = mutableListOf<A2uiMessage>()
        var eventsReused = 0
        var eventsProjected = 0
        val nextCache = HashMap<TimelineProjectionKey, CachedTimelineProjectionEvent>(timeline.events.size)
        val live = ArrayList<UiMessage>(timeline.events.size)

        timeline.events.forEach { event ->
            val key = event.projectionKey()
            val cached = projectionCache[key]?.takeIf { it.event == event }
            val projected = if (cached != null) {
                eventsReused++
                cached
            } else {
                eventsProjected++
                val projection = event.projectForCache()
                CachedTimelineProjectionEvent(
                    event = event,
                    uiMessage = projection.uiMessage,
                    a2uiMessages = projection.a2uiMessages,
                )
            }
            nextCache[key] = projected
            a2uiMessages += projected.a2uiMessages
            projected.uiMessage?.let(live::add)
        }
        projectionCache = nextCache

        val combined = combineOlderPrefix(prefix, live)
        val ui = combined.toImmutableList()
        val tailIsAssistant = timeline.events.lastOrNull().let {
            it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.ASSISTANT
        }
        val anyLettaServerLocalPending = timeline.events.any {
            it is TimelineEvent.Local &&
                it.deliveryState == DeliveryState.SENDING
        }
        val result = TimelineProjection(
            ui = ui,
            tailIsAssistant = tailIsAssistant,
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = ui.any { !it.isPending },
            a2uiMessages = a2uiMessages,
        )
        Telemetry.event(
            "TimelineSync",
            "uiProjection.snapshot",
            "conversationId" to timeline.conversationId,
            "eventsTotal" to timeline.events.size,
            "eventsReused" to eventsReused,
            "eventsProjected" to eventsProjected,
            "messageCount" to ui.size,
            "prefixCount" to prefix.size,
            "toolCardCount" to ui.sumOf { it.toolCardCount() },
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
        return result
    }

    private fun TimelineEvent.projectForCache(): CachedTimelineProjection {
        val extractedA2uiMessages = mutableListOf<A2uiMessage>()
        val uiMessage = timelineEventToUiMessage(this)
            ?.extractA2uiHistoryInto(extractedA2uiMessages)
            ?.takeUnless { it.isEmptyAfterA2uiExtraction() }
        return CachedTimelineProjection(
            uiMessage = uiMessage,
            a2uiMessages = extractedA2uiMessages.toList(),
        )
    }

    private fun TimelineEvent.projectionKey(): TimelineProjectionKey = when (this) {
        is TimelineEvent.Confirmed -> TimelineProjectionKey("confirmed", serverId, messageType.name)
        is TimelineEvent.Local -> TimelineProjectionKey("local", otid, messageType.name)
    }

    private fun UiMessage.toolCardCount(): Int =
        toolCalls?.size ?: if (role == "tool" || generatedUi != null || approvalRequest != null || approvalResponse != null) 1 else 0

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
    )

    private data class TimelineProjectionKey(
        val source: String,
        val id: String,
        val type: String,
    )

    private data class CachedTimelineProjection(
        val uiMessage: UiMessage?,
        val a2uiMessages: List<A2uiMessage>,
    )

    private data class CachedTimelineProjectionEvent(
        val event: TimelineEvent,
        val uiMessage: UiMessage?,
        val a2uiMessages: List<A2uiMessage>,
    )
}
