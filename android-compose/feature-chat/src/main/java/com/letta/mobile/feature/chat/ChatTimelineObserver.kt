package com.letta.mobile.feature.chat

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.TimelineSyncEvent
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
) {
    private var observerJob: Job? = null
    private var hydrateSignalJob: Job? = null

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
    }

    fun start(conversationId: String) {
        val convIdSame = observerConversationId == conversationId
        val jobActive = observerJob?.isActive == true
        if (convIdSame && jobActive) return

        observerJob?.cancel()
        hydrateSignalJob?.cancel()
        olderMessagesPrefix = "" to emptyList()
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
                    val live = timeline.events.mapNotNull { timelineEventToUiMessage(it) }
                    val prefix = olderPrefixFor(conversationId)
                    val combined = combineOlderPrefix(prefix, live)
                    val ui = combined.toImmutableList()
                    val tailIsAssistant = timeline.events.lastOrNull().let {
                        it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.ASSISTANT
                    }
                    val anyLettaServerLocalPending = timeline.events.any {
                        it is TimelineEvent.Local &&
                            it.deliveryState == DeliveryState.SENDING &&
                            it.source != MessageSource.CLIENT_MODE_HARNESS
                    }
                    val clearLoading = ui.isNotEmpty()
                    val anyConfirmed = ui.any { !it.isPending }
                    val newHasMoreOlder = if (anyConfirmed) true else uiState.value.hasMoreOlderMessages

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
}
