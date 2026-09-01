package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.data.timeline.Timeline
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.letta.mobile.ui.chat.render.ChatPresenceSignals
import com.letta.mobile.ui.chat.render.ChatTimelinePresenter
import com.letta.mobile.ui.chat.render.TimelineProjection
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.util.Telemetry

import kotlin.time.Duration.Companion.milliseconds
import com.letta.mobile.data.timeline.TimelineAcquisitionProvenance
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
    // letta-mobile-c4igq.7: transport-owned "a chat turn is in flight" signal
    // (true from turn start until the real terminal, across all tool rounds).
    // Holds presence across inter-round gaps so a multi-tool turn stays "working"
    // and does not flicker / look finished. Defaults to always-false so existing
    // callers/tests are unaffected until wired.
    private val hasActiveChatTurn: () -> Boolean = { false },
    private val a2uiThinkingStartMessageCount: () -> Int?,
    private val clearA2uiThinkingOnResponse: () -> Unit,
    private val isFollowingDuplicateInitialMessageInFlight: () -> Boolean,
    private val clearFollowingDuplicateInitialMessageInFlight: () -> Unit,
    // letta-mobile-ah1ng: every projection publication below routes through
    // this hook so terminal runs reconcile on EVERY publication — not only
    // on the global previous.isStreaming && !next.isStreaming edge, which
    // missed completed runs first seen via hydration/reconnect and
    // presence/projection orderings.
    private val reconcileCollapsedRunsOnProjection: (previous: ChatUiState, next: ChatUiState) -> ChatUiState,
    private val syncA2uiHistorySnapshot: (conversationId: String, messages: List<A2uiMessage>) -> Map<String, A2uiSurfaceState> =
        { _, _ -> emptyMap() },
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // letta-mobile-yflpp: minimum gap between projection writes (COALESCE). Set
    // to 0 in unit tests so virtual-clock emissions stay synchronous.
    private val projectionFrameIntervalMs: Long = PROJECTION_FRAME_INTERVAL_MS,
    private val hydrationIdentity: (String?, String) -> ChatHydrationTrace.Identity = { agentId, conversationId ->
        ChatHydrationTrace.Identity(agentId = agentId, conversationId = conversationId)
    },
) {
    private var observerJob: Job? = null
    private var hydrateSignalJob: Job? = null
    /** True after Hydrated reported events while the UI still has no rows. */
    private var awaitingProjectionAfterHydrate: Boolean = false

    /** Shared presentation core: projection (cache + incremental tail) + presence. */
    private val presenter = ChatTimelinePresenter()

    /** Agent/conversation id pair the current observer job is bound to. */
    private var observerBinding: TimelineObserverBinding? = null
    private var hydrationGeneration: ChatHydrationTrace.Generation? = null
    private var warmBootstrap: WarmBootstrap? = null

    fun stop() {
        observerJob?.cancel()
        observerJob = null
        hydrateSignalJob?.cancel()
        hydrateSignalJob = null
        observerBinding = null
        hydrationGeneration = null
        warmBootstrap = null
        awaitingProjectionAfterHydrate = false
        presenter.reset()
    }

    fun start(conversationId: String) = start(agentId = null, conversationId = conversationId)

    /**
     * letta-mobile-grrhq: provenance for the acquisition this bind performs.
     * Deliberately a SEPARATE field rather than a [TimelineObserverBinding]
     * member — binding equality drives rebind/keep-projection decisions, so
     * putting diagnostic data in it would change behavior.
     */
    private var pendingProvenance: TimelineAcquisitionProvenance = TimelineAcquisitionProvenance.UNSPECIFIED

    fun start(
        agentId: String?,
        conversationId: String,
        provenance: TimelineAcquisitionProvenance = TimelineAcquisitionProvenance.UNSPECIFIED,
    ) {
        pendingProvenance = provenance
        val binding = TimelineObserverBinding(agentId = agentId, conversationId = conversationId)
        val bindingSame = observerBinding == binding
        val jobActive = observerJob?.isActive == true
        if (bindingSame && jobActive) return

        observerJob?.cancel()
        hydrateSignalJob?.cancel()
        observerBinding = binding
        warmBootstrap = null
        if (!bindingSame) {
            presenter.reset()
            val cachedTimeline = timelineRepository.peekCached(agentId, conversationId)
            if (cachedTimeline != null) {
                // Project the target loop synchronously so its identity and rows
                // become visible in one publication. Empty is a ready cache hit.
                val previous = uiState.value
                val projection = presenter.project(
                    timeline = cachedTimeline,
                    prefix = presenter.olderPrefixFor(conversationId),
                    previousState = previous,
                    isActiveRunStreaming = hasActiveChatTurn(),
                    ownAgentId = agentId,
                )
                publishProjection(binding, projection, generation = null).let {
                    uiState.value = reconcileCollapsedRunsOnProjection(
                        it.previous,
                        it.next.copy(isLoadingMessages = false),
                    )
                }
                warmBootstrap = WarmBootstrap(binding, cachedTimeline)
            } else {
                uiState.value = uiState.value.copy(
                    messages = kotlinx.collections.immutable.persistentListOf(),
                    messageListChange = com.letta.mobile.data.chat.projection.ChatMessageListChange.Full,
                    isLoadingMessages = true,
                )
            }
        } else {
            // Same binding rebind (job died / restart): keep projection
            // cache + visible messages so Compose retains item identity.
            uiState.value = uiState.value.copy(isLoadingMessages = true)
        }
        hydrationGeneration = ChatHydrationTrace.begin(hydrationIdentity(agentId, conversationId), reuseIfActive = true)
        observerJob = launchObserver(binding, hydrationGeneration)
    }

    private fun launchObserver(
        binding: TimelineObserverBinding,
        generation: ChatHydrationTrace.Generation?,
    ): Job = scope.launch {
            val agentId = binding.agentId
            val conversationId = binding.conversationId
            val provenance = pendingProvenance
            val flow = try {
                timelineRepository.observe(agentId, conversationId, provenance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("AdminChatViewModel", "Timeline observe failed", failure)
                if (observerBinding == binding) {
                    uiState.value = uiState.value.copy(
                        error = "Couldn't sync conversation — pull to refresh",
                        isLoadingMessages = false,
                    )
                }
                return@launch
            }

            if (observerBinding != binding) return@launch
            val loop = timelineRepository.getOrCreate(agentId, conversationId, provenance)
            if (observerBinding != binding) return@launch
            currentConversationTracker.setCurrent(conversationId)
            hydrateSignalJob = launchHydrationCollector(loop, binding, generation)

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
                    if (observerBinding != binding) return@collect
                    if (Telemetry.isTimelineSyncGateDebugEnabled()) {
                        Telemetry.event(
                            "TimelineSyncIngest", "gate6.timelineCollected",
                            "conversationId" to conversationId,
                            "count" to timeline.events.size,
                            level = Telemetry.Level.DEBUG,
                        )
                    }
                    val prefix = presenter.olderPrefixFor(conversationId)
                    val previousState = uiState.value
                    val projection = withContext(projectionDispatcher) {
                        presenter.project(
                            timeline = timeline,
                            prefix = prefix,
                            previousState = previousState,
                            // letta-mobile-dir4k.1: thread the transport's
                            // "turn in flight" latch into the projector so
                            // [TimelineProjection.anyRunActive] stays true
                            // across inter-tool-call gaps where no
                            // `isPending=true` message is currently in `live`.
                            // Without this the Thinking chip drops mid-turn
                            // between tools (regression introduced by PR
                            // #1119 — see bead letta-mobile-dir4k.1).
                            isActiveRunStreaming = hasActiveChatTurn(),
                            ownAgentId = binding.agentId,
                        )
                    }

                    if (observerBinding != binding) return@collect

                    if (suppressWarmBootstrapReplay(binding, timeline, projection, generation)) {
                        return@collect
                    }

                    // letta-mobile-yflpp DEDUPE: a no-op streaming tick (the
                    // tail event was re-emitted unchanged) projects to a UI
                    // byte-identical to the screen. Skip the uiState write so we
                    // don't allocate a new ChatUiState and force a recomposition
                    // storm over every tool card. Telemetry was already emitted
                    // as uiProjection.suppressed by the presenter.
                    if (projection.noChange) {
                        publishPresenceOnly(binding, projection, generation)?.let {
                            uiState.value = reconcileCollapsedRunsOnProjection(it.previous, it.next)
                        }
                        return@collect
                    }
                    publishProjection(binding, projection, generation).let {
                        uiState.value = reconcileCollapsedRunsOnProjection(it.previous, it.next)
                    }

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
                        delay(projectionFrameIntervalMs.milliseconds)
                    }
                }
            } finally {
                hydrateSignalJob?.cancel()
            }
        }

    private fun launchHydrationCollector(
        loop: TimelineSyncLoop,
        binding: TimelineObserverBinding,
        generation: ChatHydrationTrace.Generation?,
    ): Job = scope.launch {
        loop.events.collect { ev ->
            if (observerBinding != binding) return@collect
            when (ev) {
                is TimelineSyncEvent.ReconcileError -> {
                    val previous = uiState.value
                    uiState.value = reconcileCollapsedRunsOnProjection(
                        previous,
                        previous.copy(
                            error = "Couldn't sync agent reply — pull to refresh",
                            isStreaming = false,
                            isAgentTyping = false,
                        ),
                    )
                }
                else -> handleHydrationEvent(ev, binding, generation)
            }
        }
    }

    private fun handleHydrationEvent(
        event: TimelineSyncEvent,
        binding: TimelineObserverBinding,
        generation: ChatHydrationTrace.Generation?,
    ) {
        when (event) {
            is TimelineSyncEvent.Hydrated -> {
                generation?.let { ChatHydrationTrace.sourceReady(it, source = "timeline", count = event.messageCount) }
                android.util.Log.i(
                    "AdminChatViewModel",
                    "Timeline ready conv=${binding.conversationId} count=${event.messageCount}",
                )
                val previous = uiState.value
                awaitingProjectionAfterHydrate = event.messageCount > 0 && previous.messages.isEmpty()
                uiState.value = previous.copy(isLoadingMessages = awaitingProjectionAfterHydrate)
            }
            is TimelineSyncEvent.HydrateFailed -> {
                generation?.let { ChatHydrationTrace.sourceUnavailable(it, source = "timeline") }
                awaitingProjectionAfterHydrate = false
                uiState.value = uiState.value.copy(isLoadingMessages = false)
            }
            else -> Unit
        }
    }

    private fun publishProjection(
        binding: TimelineObserverBinding,
        projection: TimelineProjection,
        generation: ChatHydrationTrace.Generation?,
    ): UiStatePublication {
        val ui = projection.ui
        val surfaces = syncA2uiHistorySnapshot(binding.conversationId, projection.a2uiMessages)
        val clearLoading = ui.isNotEmpty() || awaitingProjectionAfterHydrate
        if (clearLoading) awaitingProjectionAfterHydrate = false
        if (isFollowingDuplicateInitialMessageInFlight() && projection.tailIsAssistant) {
            clearFollowingDuplicateInitialMessageInFlight()
        }
        val previous = uiState.value
        val thinkingStart = a2uiThinkingStartMessageCount()
        val responseArrived = thinkingStart != null && ui
            .drop(thinkingStart)
            .any { it.role == "assistant" && !it.isReasoning }
        if (responseArrived) clearA2uiThinkingOnResponse()
        val presentation = presenter.present(
            projection = projection,
            signals = presenceSignals(
                PresenceRequest(binding, projection, previous, thinkingStart, responseArrived),
            ),
            previousIsStreaming = previous.isStreaming,
            previousIsAgentTyping = previous.isAgentTyping,
        )
        recordPresentation(
            PresentationRecord(generation, projection, previous, presentation.isStreaming, presentation.isAgentTyping),
        )
        return UiStatePublication(
            previous = previous,
            next = previous.copy(
                messages = ui,
                messageListChange = projection.messageListChange,
                a2uiSurfaces = surfaces.toPersistentMap(),
                isLoadingMessages = if (clearLoading) false else previous.isLoadingMessages,
                isStreaming = presentation.isStreaming,
                isAgentTyping = presentation.isAgentTyping,
                hasMoreOlderMessages = projection.anyConfirmed || previous.hasMoreOlderMessages,
            ),
        )
    }

    private fun recordPresentation(record: PresentationRecord) {
        val generation = record.generation ?: return
        ChatHydrationTrace.presentationPublished(
            generation,
            commitReason = record.projection.messageListChange::class.simpleName ?: "unknown",
            messageCount = record.projection.ui.size,
            missingOptionalSources = if (record.projection.a2uiMessages.isEmpty()) "a2ui" else "none",
        )
        if (record.isStreaming != record.previous.isStreaming ||
            record.isAgentTyping != record.previous.isAgentTyping
        ) {
            ChatHydrationTrace.activityChanged(
                generation,
                active = record.isStreaming || record.isAgentTyping,
                reason = "projection_presence",
            )
        }
    }

    private fun publishPresenceOnly(
        binding: TimelineObserverBinding,
        projection: TimelineProjection,
        generation: ChatHydrationTrace.Generation?,
    ): UiStatePublication? {
        val previous = uiState.value
        if (isFollowingDuplicateInitialMessageInFlight() && projection.tailIsAssistant) {
            clearFollowingDuplicateInitialMessageInFlight()
        }
        val thinkingStart = a2uiThinkingStartMessageCount()
        val responseArrived = thinkingStart != null && projection.ui
            .drop(thinkingStart)
            .any { it.role == "assistant" && !it.isReasoning }
        if (responseArrived) clearA2uiThinkingOnResponse()
        val presentation = presenter.present(
            projection = projection,
            signals = presenceSignals(
                PresenceRequest(binding, projection, previous, thinkingStart, responseArrived),
            ),
            previousIsStreaming = previous.isStreaming,
            previousIsAgentTyping = previous.isAgentTyping,
        )
        if (presentation.isStreaming == previous.isStreaming &&
            presentation.isAgentTyping == previous.isAgentTyping
        ) return null
        generation?.let {
            ChatHydrationTrace.activityChanged(
                it,
                active = presentation.isStreaming || presentation.isAgentTyping,
                reason = "presence_only",
            )
        }
        return UiStatePublication(
            previous = previous,
            next = previous.copy(
                isStreaming = presentation.isStreaming,
                isAgentTyping = presentation.isAgentTyping,
            ),
        )
    }

    private fun presenceSignals(request: PresenceRequest) = ChatPresenceSignals(
        replyStreaming = activeReplyStreams.value.contains(request.binding.conversationId) ||
            request.projection.hasGrowingPassiveModelTail(request.previous),
        clientModeStreamInFlight = isClientModeStreamInFlight(),
        a2uiThinkingActive = request.thinkingStart != null && !request.responseArrived,
        duplicateInitialMessageInFlight = isFollowingDuplicateInitialMessageInFlight(),
        turnInFlight = hasActiveChatTurn(),
    )

    fun mergeOlderPage(
        conversationId: String,
        olderMessages: List<UiMessage>,
        existingMessages: List<UiMessage>,
    ): List<UiMessage> = presenter.mergeOlderPage(conversationId, olderMessages, existingMessages)

    /**
     * Sliding-window release: shrinks the resident message list back down
     * once the user has scrolled away from the older-page prefix they
     * pulled in, so the window oscillates around the cap instead of only
     * ever growing. See [ChatTimelinePresenter.releaseOlderPrefix].
     */
    fun releaseOlderMessages(
        conversationId: String,
        currentMessages: List<UiMessage>,
    ): List<UiMessage> = presenter.releaseOlderPrefix(conversationId, currentMessages)

    /**
     * A desktop-origin observer turn is absent from Android's initiator-owned
     * activeReplyStreams. Detect actual cumulative assistant growth instead:
     * stable message identity + longer text on an incremental tail update.
     * This cannot light up an old settled assistant merely because an unrelated
     * user send toggled state, and it gives the smoother live cadence immediately.
     */
    private fun TimelineProjection.hasGrowingPassiveModelTail(previous: ChatUiState): Boolean {
        // Only a REPLACE of the same tail row proves an existing model tail is
        // still growing. AppendTail proves a new row arrived (it may already be
        // a completed reply fanned out from another device), so it must not
        // force streaming presence — the next growing replace will.
        if (messageListChange != com.letta.mobile.data.chat.projection.ChatMessageListChange.ReplaceTail) return false
        val current = ui.lastOrNull() ?: return false
        if (!current.isModelOutputRow(tailIsAssistant)) return false
        val prior = previous.messages.lastOrNull() ?: return false
        return current.id == prior.id && current.content.length > prior.content.length
    }

    /** Assistant-role reasoning or final-answer row (the model-output tail). */
    private fun UiMessage.isModelOutputRow(tailIsAssistant: Boolean): Boolean =
        role == "assistant" && (isReasoning || tailIsAssistant)

    private data class UiStatePublication(
        val previous: ChatUiState,
        val next: ChatUiState,
    )

    private data class PresentationRecord(
        val generation: ChatHydrationTrace.Generation?,
        val projection: TimelineProjection,
        val previous: ChatUiState,
        val isStreaming: Boolean,
        val isAgentTyping: Boolean,
    )

    private data class PresenceRequest(
        val binding: TimelineObserverBinding,
        val projection: TimelineProjection,
        val previous: ChatUiState,
        val thinkingStart: Int?,
        val responseArrived: Boolean,
    )

    private data class TimelineObserverBinding(
        val agentId: String?,
        val conversationId: String,
    )

    private data class WarmBootstrap(
        val binding: TimelineObserverBinding,
        val timeline: Timeline,
    )

    private fun suppressWarmBootstrapReplay(
        binding: TimelineObserverBinding,
        timeline: Timeline,
        projection: TimelineProjection,
        generation: ChatHydrationTrace.Generation?,
    ): Boolean {
        val bootstrap = warmBootstrap?.takeIf { it.binding == binding } ?: return false
        warmBootstrap = null
        if (timeline !== bootstrap.timeline || !projection.noChange) return false

        publishPresenceOnly(binding, projection, generation)?.let { publication ->
            val reconciled = reconcileCollapsedRunsOnProjection(publication.previous, publication.next)
            if (reconciled != publication.previous) uiState.value = reconciled
        }
        Telemetry.event(
            "TimelineSync", "warmBootstrap.suppressed",
            "conversationId" to binding.conversationId,
            "eventCount" to timeline.events.size,
        )
        return true
    }

    private companion object {
        // letta-mobile-yflpp COALESCE: minimum gap between projection writes.
        // ~one frame at 60Hz; a tight server delta stream collapses to frame
        // cadence instead of ~20 recompositions/sec, keeping the UI thread free
        // for Compose pointer hit-testing so tool-card taps land mid-stream.
        const val PROJECTION_FRAME_INTERVAL_MS = 16L
    }
}
