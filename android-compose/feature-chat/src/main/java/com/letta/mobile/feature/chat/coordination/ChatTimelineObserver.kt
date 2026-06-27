package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.TimelineSyncEvent
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
import com.letta.mobile.ui.chat.render.ChatPresenceSignals
import com.letta.mobile.ui.chat.render.ChatTimelinePresenter
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.feature.chat.screen.AdminChatViewModel

/**
 * Owns the long-lived timeline subscriptions and projection of timeline events
 * into [ChatUiState]. [AdminChatViewModel] still decides when to bind a
 * conversation, but this collaborator owns observer job lifecycle, hydration
 * signals, older-page prefix merging, and streaming/typing flag projection.
 *
 * The Timeline→presentation transform (incremental tail cache, dedupe, A2UI
 * extraction, [com.letta.mobile.data.chat.projection.ChatMessageListChange], and
 * streaming/typing derivation) lives in the shared [ChatTimelinePresenter] so
 * Android and desktop share identical output. This class keeps only the
 * Android-side driver: the subscription loop, the frame-pace coalescing, the
 * hydrate-signal handling, and computing the platform stream signals the
 * presenter's presence derivation needs.
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

    /** Shared presentation core: projection (cache + incremental tail) + presence. */
    private val presenter = ChatTimelinePresenter()

    /** Agent/conversation id pair the current observer job is bound to. */
    private var observerBinding: TimelineObserverBinding? = null

    fun stop() {
        observerJob?.cancel()
        observerJob = null
        hydrateSignalJob?.cancel()
        hydrateSignalJob = null
        observerBinding = null
        presenter.reset()
    }

    fun start(conversationId: String) = start(agentId = null, conversationId = conversationId)

    fun start(agentId: String?, conversationId: String) {
        val binding = TimelineObserverBinding(agentId = agentId, conversationId = conversationId)
        val bindingSame = observerBinding == binding
        val jobActive = observerJob?.isActive == true
        if (bindingSame && jobActive) return

        observerJob?.cancel()
        hydrateSignalJob?.cancel()
        presenter.reset()
        observerBinding = binding
        observerJob = scope.launch {
            val flow = try {
                timelineRepository.observe(agentId, conversationId)
            } catch (e: Exception) {
                android.util.Log.e("AdminChatViewModel", "Timeline observe failed", e)
                uiState.value = uiState.value.copy(error = "Timeline init failed: ${e.message}")
                return@launch
            }

            val loop = timelineRepository.getOrCreate(agentId, conversationId)
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
                                    error = "Couldn't sync agent reply â€” pull to refresh",
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
                // a StateFlow, which is already conflated â€” a collector that
                // suspends (e.g. on the projection dispatcher or the frame-pace
                // delay below) only ever sees the LATEST value when it resumes,
                // never a backlog. Together with that pacing delay the
                // projection runs at most ~once per frame instead of once per
                // delta, so Compose hit-testing / gesture handling get a clean
                // pass and tool-card taps land mid-stream.
                flow.collect { timeline ->
                    val prefix = presenter.olderPrefixFor(conversationId)
                    val previousState = uiState.value
                    val projection = withContext(projectionDispatcher) {
                        presenter.project(
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
                    // as uiProjection.suppressed by the presenter.
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
                    // Platform stream signals — computed on the collect coroutine
                    // (NOT the projection dispatcher) because they read/clear
                    // ViewModel flags and inspect the projected list. The shared
                    // presenter then derives the streaming/typing presence.
                    // prev is captured before the a2ui side effect, matching the
                    // pre-refactor ordering.
                    val prev = uiState.value
                    val a2uiStartMessageCount = a2uiThinkingStartMessageCount()
                    val a2uiResponseArrived = a2uiStartMessageCount != null && ui
                        .drop(a2uiStartMessageCount)
                        .any { it.role == "assistant" && !it.isReasoning }
                    if (a2uiResponseArrived) {
                        clearA2uiThinkingOnResponse()
                    }
                    val presentation = presenter.present(
                        projection = projection,
                        signals = ChatPresenceSignals(
                            replyStreaming = activeReplyStreams.value.contains(conversationId),
                            clientModeStreamInFlight = isClientModeStreamInFlight(),
                            a2uiThinkingActive = a2uiStartMessageCount != null && !a2uiResponseArrived,
                            duplicateInitialMessageInFlight = isFollowingDuplicateInitialMessageInFlight(),
                        ),
                        previousIsStreaming = prev.isStreaming,
                        previousIsAgentTyping = prev.isAgentTyping,
                    )
                    val nextIsStreaming = presentation.isStreaming
                    val nextIsAgentTyping = presentation.isAgentTyping

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
                    // lost â€” they just collapse to frame cadence. A zero
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
    ): List<UiMessage> = presenter.mergeOlderPage(conversationId, olderMessages, existingMessages)

    private data class TimelineObserverBinding(
        val agentId: String?,
        val conversationId: String,
    )

    private companion object {
        // letta-mobile-yflpp COALESCE: minimum gap between projection writes.
        // ~one frame at 60Hz; a tight server delta stream collapses to frame
        // cadence instead of ~20 recompositions/sec, keeping the UI thread free
        // for Compose pointer hit-testing so tool-card taps land mid-stream.
        const val PROJECTION_FRAME_INTERVAL_MS = 16L
    }
}
