package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.recompile.ConversationRecompileController
import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatRunInterruptTelemetry
import com.letta.mobile.data.chat.runtime.ChatRunInterrupter
import com.letta.mobile.data.chat.runtime.ChatStopRequest
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.presence.ActiveTurnCoordinator
import com.letta.mobile.data.presence.ActiveTurnIdentity
import com.letta.mobile.data.presence.ActiveTurnPhase
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.ui.chat.render.ChatTimelinePresenter
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.GoalStatusUi
import com.letta.mobile.ui.chat.render.chatSearchLocalResults
import com.letta.mobile.ui.chat.render.chatSearchRemoteHits
import com.letta.mobile.ui.chat.render.mergeChatSearchHits
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The conversation a [TimelineChatSessionPort] serves. One port per conversation: a host that
 * switches conversations builds a new one (and closes the old one's timeline).
 */
data class TimelineChatTarget(
    val agentId: String,
    val agentName: String,
    val conversationId: String,
)

/**
 * The timeline loop's surface a [TimelineChatSessionPort] reads and drives. A seam over
 * [com.letta.mobile.data.timeline.TimelineSyncLoop] so the port is testable without a transport;
 * [TimelineSyncLoopSession] is the production binding.
 */
interface TimelineChatSession {
    val timeline: StateFlow<Timeline>
    val events: SharedFlow<TimelineSyncEvent>

    suspend fun hydrate()

    suspend fun send(text: String, attachments: List<MessageContentPart.Image>)
}

/**
 * The run controls the owner's transport offers. Null means the transport cannot, and the port
 * reports that through [ChatSurfaceCapabilities] so the page hides the control.
 */
data class TimelineChatRunControls(
    /** Asks the server to stop the conversation's active run; true once it went out (false clears the run locally). */
    val stopRun: (suspend () -> Boolean)? = null,
    val answerApproval: (suspend (ChatApprovalAnswer) -> Unit)? = null,
)

/** What the port is built from besides the session itself. */
data class TimelineChatSessionOptions(
    val controls: TimelineChatRunControls = TimelineChatRunControls(),
    /**
     * The host's own approval state (answers in flight, queued offline,
     * parked details), e.g. the shared [com.letta.mobile.data.chat.approval.ChatApprovalAnswerer]'s.
     * Null keeps the port's own in-flight tracking only, as the web client does.
     */
    val approvals: ChatApprovalStateSource? = null,
    /** The conversation's working directory; null: no chip, capability off. */
    val workingDirectory: ChatWorkingDirectorySource? = null,
    /** The conversation's permission-mode chip; null shows none. */
    val permissionModes: ChatPermissionModeSource? = null,
    /** The conversation's model chip and switch; null: no chip, capability off. */
    val models: ChatModelSource? = null,
    /** The conversation's send queue; null: a send waits for the run to end. */
    val sendQueue: ChatSendQueueSource? = null,
    /** The composer's commands, mentionables, context usage and placeholder. */
    val composerHost: StateFlow<ChatComposerHostInputs>? = null,
    /** Host snapshots and stop control for the existing process shelf. */
    val backgroundProcesses: StateFlow<ChatComposerHostInputs>? = null,
    /** Where an A2UI surface's actions go; null drops them. */
    val onA2uiAction: ((A2uiAction) -> Unit)? = null,
    /** Fork, edit and resend, and rerun; null: capabilities off. */
    val branching: ChatBranchingSource? = null,
    /** `/recompile` and its status; null keeps the status idle. */
    val recompile: ConversationRecompileController? = null,
    /** The conversation's live status line; null keeps it hidden. */
    val liveStatus: Flow<RuntimeLiveStatus>? = null,
    val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
    /** Trusted owner for this exact target; null retains the legacy timeline stop lifecycle. */
    val activeTurnCoordinator: ActiveTurnCoordinator? = null,
    /**
     * Letta's message search for the conversation's agent: what the host's message repository
     * answers for [MessageSearchRequest], parsed ([] when it finds nothing). The port keeps the
     * chat-search state itself - the timeline's own matches answer the query immediately, these
     * hits merge in after the same short debounce Android's chat ran. Null keeps
     * [ChatSurfaceCapabilities.search] off, so the page hides its search bar.
     */
    val search: (suspend (MessageSearchRequest) -> List<ParsedSearchMessage>)? = null,
    /**
     * Reads the conversation's goal status as the page shows it ([GoalStatusUi]; null when the
     * target has no active goal), re-read by [ChatActions.refreshGoalStatus]. The port owns the
     * loading flag; a push-driven host re-reads by calling refresh again. Null keeps
     * [ChatSurfaceCapabilities.goals] off, so the page hides the goal card.
     */
    val goalStatus: (suspend () -> GoalStatusUi?)? = null,
)

/**
 * A [ChatSessionPort] owned entirely in commonMain, over one conversation's
 * shared timeline loop.
 *
 * The timeline is projected by the same [ChatTimelinePresenter] Android and desktop use (rows, the
 * list-change hint, streaming and typing presence), the composer runs on [ChatComposerPolicy], and
 * sends go through the loop's own optimistic-send pipeline. A platform binds it by handing over a
 * [TimelineChatSession] and the run controls its transport has; the web client is the first host
 * that has no conversation owner of its own.
 *
 * Capabilities it does not have yet (paged history, re-run) are reported off, so the page hides
 * them; search and goal status come from the same options' hooks
 * ([TimelineChatSessionOptions.search], [TimelineChatSessionOptions.goalStatus]).
 */
class TimelineChatSessionPort(
    private val session: TimelineChatSession,
    private val target: TimelineChatTarget,
    private val scope: CoroutineScope,
    private val options: TimelineChatSessionOptions = TimelineChatSessionOptions(),
) : ChatSessionPort {
    private val presenter = ChatTimelinePresenter()
    private val draft = MutableStateFlow(TimelineComposerDraft())
    private val run = MutableStateFlow(TimelineRunState())
    private val local = MutableStateFlow(TimelineLocalState())

    /** The page state as the timeline presents it, before a local stop clear is laid over it. */
    private val presented: StateFlow<ChatUiState> = timelineInputs()
        .runningFold(null as ChatUiState?) { previous, inputs -> present(inputs, previous) }
        .map { it ?: initialUiState() }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialUiState())

    /** The port's page-local fields (chat search and the goal status), laid over each presentation. */
    private val page = MutableStateFlow(ChatUiState())

    /** The local run clear with the page's own fields, so the projection below stays five slots. */
    private val pageOverlay = combine(
        run.map { it.clearedLocally }.distinctUntilChanged(),
        page,
    ) { cleared, fields -> cleared to fields }

    override val uiState: StateFlow<ChatUiState> = combine(
        presented,
        pageOverlay,
        options.sendQueue?.queue ?: flowOf(ConversationSendQueue()),
        options.activeTurnCoordinator?.state ?: flowOf(null),
        options.activeTurnCoordinator?.outcome ?: flowOf(null),
    ) { ui, overlay, queue, turn, outcome ->
        val projected = when (turn) {
            null -> ui.withRunClearedLocally(overlay.first)
            else -> ui.withActiveTurn(turn, outcome)
        }
        projected.copy(sendQueue = queue).withPageFields(overlay.second)
    }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialUiState())

    /** Stop's lifecycle, the same shared state machine Android and desktop run. */
    private val interrupter = fallbackInterrupter()
    private fun fallbackInterrupter(): ChatRunInterrupter<String>? {
        if (options.activeTurnCoordinator != null) return null
        return ChatRunInterrupter(
            scope = scope,
            host = TimelineRunStopHost(updateRun = ::updateRun, onClearedLocally = ::watchClearedRunEnd),
            telemetry = ChatRunInterruptTelemetry(TIMELINE_STOP_TELEMETRY_TAG),
        )
    }
    private var clearedRunWatch: Job? = null

    /** What the host's extension points add, read the way desktop's port reads them. */
    private val extensions = ChatSessionExtensions(options, scope)

    override val composer: StateFlow<ChatComposerUiState> = combine(draft, uiState, extensions.chrome) { current, ui, chrome ->
        val gate = TimelineSendGate(ui.isRunInFlight, chrome.canQueueWhileStreaming)
        timelineComposerUiState(current, gate, options).withChrome(chrome)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        timelineComposerUiState(draft.value, sendGate(runInFlight = false), options).withChrome(extensions.chrome.value),
    )

    override val capabilities: StateFlow<ChatSurfaceCapabilities> = extensions.facts
        .map(::currentCapabilities)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), currentCapabilities(extensions.facts.value))

    private fun currentCapabilities(facts: ChatExtensionFacts) = timelineCapabilities(
        controls = options.controls,
        search = options.search != null,
        goals = options.goalStatus != null,
    ).withExtensions(facts)

    override val liveStatus: StateFlow<RuntimeLiveStatus> get() = extensions.liveStatus

    override val recompileStatus: StateFlow<RecompileStatus> get() = extensions.recompileStatus

    /** Stable handlers bound to this session's draft, timeline and configured extension actions. */
    override val actions: ChatActions = TimelineChatActions(
        port = this,
        options = options,
        state = ChatExtensionActionState(
            local = local,
            branchView = { uiState.value.let { ChatBranchView(it.messages, it.hasMoreOlderMessages) } },
            error = { run.value.error },
        ),
    )

    init {
        options.activeTurnCoordinator?.state?.value?.let { turn ->
            require(turn.conversationId.value == target.conversationId && turn.agentId.value == target.agentId) {
                "Active turn owner must match the session target"
            }
        }
        scope.launch { session.events.collect(::onSyncEvent) }
        scope.launch { load() }
        refreshGoalStatus()
    }

    private suspend fun load() {
        run.update { it.copy(loading = true, loadError = null) }
        try {
            session.hydrate()
            run.update { it.copy(loading = false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            run.update { it.copy(loading = false, loadError = failure.message ?: LOAD_FAILED) }
        }
    }

    private fun onSyncEvent(event: TimelineSyncEvent) {
        when (event) {
            is TimelineSyncEvent.HydrateFailed -> run.update { it.copy(loading = false, loadError = event.message) }
            is TimelineSyncEvent.StreamError -> run.update { it.copy(error = event.message) }
            else -> Unit
        }
    }

    private fun timelineInputs(): Flow<TimelineInputs> =
        combine(session.timeline, run, local, options.approvals.hostApprovals()) { timeline, runState, localState, approvals ->
            TimelineInputs(timeline, runState, localState, approvals)
        }

    private fun present(inputs: TimelineInputs, previous: ChatUiState?): ChatUiState {
        val prior = previous ?: initialUiState()
        val projection = presenter.project(
            timeline = inputs.timeline,
            prefix = emptyList(),
            previousState = prior,
            isActiveRunStreaming = false,
            ownAgentId = target.agentId,
        )
        val presentation = presenter.present(projection, IdlePresenceSignals)
        return timelineChatUiState(target, presentation, inputs.run, inputs.local)
            .withHostApprovals(inputs.approvals, inputs.run.submittingApprovals)
    }

    private fun initialUiState(): ChatUiState {
        val initial = ChatUiState(agentName = target.agentName, agentId = target.agentId)
        val owner = options.activeTurnCoordinator ?: return initial
        return initial.withActiveTurn(owner.state.value, owner.outcome.value)
    }

    internal fun updateDraft(transform: (TimelineComposerDraft) -> TimelineComposerDraft) = draft.update(transform)

    internal fun updateRun(transform: (TimelineRunState) -> TimelineRunState) = run.update(transform)

    internal fun sendDraft() {
        if (options.branching?.runBranchCommand(draft.value.state.text) == true) {
            draft.update { it.withText("") }
            return
        }
        val gate = sendGate()
        if (gate.blocked) return
        val sendDraft = ChatComposerPolicy.beginSend(draft.value.state) ?: return
        draft.value = TimelineComposerDraft(sendDraft.nextState)
        launchSend(TimelineSend(sendDraft.text, sendDraft.attachments), queued = gate.runInFlight)
    }

    /** Sends [send] without touching the draft (starter prompts, "send again"). */
    internal fun sendDirect(send: TimelineSend) {
        val gate = sendGate()
        if (send.text.isBlank() || gate.blocked) return
        launchSend(send, queued = gate.runInFlight)
    }

    private fun sendGate(runInFlight: Boolean = uiState.value.isRunInFlight) =
        TimelineSendGate(runInFlight, extensions.canQueue.value)

    /** A [queued] send goes to the host's queue behind the run in flight, which it leaves alone. */
    private fun launchSend(send: TimelineSend, queued: Boolean) {
        if (!queued) startCleanRun()
        scope.launch {
            try {
                session.send(send.text, send.attachments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                run.update { it.copy(error = failure.message ?: SEND_FAILED) }
            }
        }
    }

    /** A new run starts clean: no earlier error, and no stop request left over from the last run. */
    private fun startCleanRun() {
        interrupter?.retire()
        clearedRunWatch?.cancel()
        run.update { it.copy(error = null, cancelling = false, clearedLocally = false) }
        local.update { it.forgetAcknowledgedOnceCleared(error = null) }
    }

    /**
     * Stop enters "stopping…" and asks the server to abort; the run settles when the timeline's own
     * run ends. A second press, an abort that did not go out, or no terminal within the shared
     * timeout drops the run locally ([TimelineRunState.clearedLocally]).
     */
    internal fun stopRun() {
        val stop = options.controls.stopRun ?: return
        options.activeTurnCoordinator?.let { coordinator ->
            stopOwnedRun(coordinator, stop)
            return
        }
        if (!uiState.value.isRunInFlight) return
        interrupter?.request(
            ChatStopRequest(
                key = target.conversationId,
                transport = STOP_TRANSPORT,
                abort = stop,
                awaitTerminal = { presented.first { !it.isRunInFlight } },
            ),
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun stopOwnedRun(coordinator: ActiveTurnCoordinator, stop: suspend () -> Boolean) {
        val captured = coordinator.state.value
        val turn = ActiveTurnIdentity(captured.runId ?: return, captured.turnId ?: return)
        coordinator.interrupt(turn, Uuid.random().toString()) { abortOwnedRun(coordinator, turn, stop) }
    }

    private suspend fun abortOwnedRun(
        coordinator: ActiveTurnCoordinator,
        turn: ActiveTurnIdentity,
        stop: suspend () -> Boolean,
    ): Boolean = try {
        stop()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (isCurrentCancellation(coordinator, turn)) {
            run.update { it.copy(error = failure.message ?: CONTROL_FAILED) }
        }
        throw failure
    }

    private fun isCurrentCancellation(coordinator: ActiveTurnCoordinator, turn: ActiveTurnIdentity): Boolean {
        val current = coordinator.state.value
        return current.phase == ActiveTurnPhase.Cancelling && current.runId == turn.runId && current.turnId == turn.turnId
    }

    /** After a local clear, the timeline's own run ending lifts the overlay. */
    private fun watchClearedRunEnd() {
        clearedRunWatch?.cancel()
        clearedRunWatch = scope.launch {
            presented.first { !it.isRunInFlight }
            run.update { it.copy(clearedLocally = false) }
        }
    }

    internal fun answerApproval(answer: ChatApprovalAnswer) {
        val submit = options.controls.answerApproval ?: return
        if (answer.requestId in run.value.submittingApprovals) return
        if (options.approvals?.submittingApprovals?.value?.contains(answer.requestId) == true) return
        run.update { it.copy(submittingApprovals = it.submittingApprovals + answer.requestId) }
        scope.launch {
            try {
                runControl { submit(answer) }
            } finally {
                run.update { it.copy(submittingApprovals = it.submittingApprovals - answer.requestId) }
            }
        }
    }

    internal fun retryLoad() {
        scope.launch { load() }
    }

    /**
     * The page's search: the timeline's own visible matches answer immediately, the host's
     * [TimelineChatSessionOptions.search] merges in after a short debounce. A newer query or a
     * clear always wins over a slower answer, as the chat search does on every client.
     */
    internal fun updateSearchQuery(query: String) {
        val search = options.search ?: return
        searchJob?.cancel()
        if (query.isBlank()) {
            clearSearch()
            return
        }
        val localNow = chatSearchLocalResults(
            AgentId(target.agentId), ConversationId(target.conversationId), query, uiState.value.messages,
        )
        page.update {
            it.copy(
                searchQuery = query,
                isSearchActive = true,
                isSearching = true,
                searchResults = localNow,
            )
        }
        searchJob = scope.launch {
            delay(SEARCH_REMOTE_DEBOUNCE_MS)
            try {
                val remote = chatSearchRemoteHits(AgentId(target.agentId), query) { request -> search(request) }
                if (page.value.searchQuery != query) return@launch
                val localNow = chatSearchLocalResults(
                    AgentId(target.agentId), ConversationId(target.conversationId), query, uiState.value.messages,
                )
                page.update { current ->
                    current.copy(searchResults = mergeChatSearchHits(localNow, remote), isSearching = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The page keeps the local matches and simply stops looking, as Android's chat does.
                page.update { current ->
                    if (current.searchQuery == query) current.copy(isSearching = false) else current
                }
            }
        }
    }

    /** The search closed: nothing is looking and no result stays on the page. */
    internal fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        page.update {
            it.copy(searchQuery = "", isSearchActive = false, isSearching = false, searchResults = persistentListOf())
        }
    }

    /** The conversation's goal, read as the port opens and every time the page asks again. */
    internal fun refreshGoalStatus() {
        val read = options.goalStatus ?: return
        goalJob?.cancel()
        goalJob = scope.launch {
            page.update { it.copy(isGoalStatusLoading = true) }
            try {
                val status = read()
                page.update { it.copy(goalStatus = status, isGoalStatusLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed read hides the card's spinner and shows the last status, as Android's chat does.
                page.update { it.copy(isGoalStatusLoading = false) }
            }
        }
    }

    /** Asks the agent to keep working on its active goal; the prompt Android's chat composes. */
    internal fun continueGoal() {
        val objective = uiState.value.goalStatus?.objective?.takeIf { it.isNotBlank() } ?: return
        sendDirect(TimelineSend(continueGoalPrompt(objective), emptyList()))
    }

    private var searchJob: Job? = null
    private var goalJob: Job? = null

    private suspend fun runControl(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            run.update { it.copy(error = failure.message ?: CONTROL_FAILED, cancelling = false) }
        }
    }

    internal val attachmentLimits: AttachmentLimits get() = options.attachmentLimits

    private data class TimelineInputs(
        val timeline: Timeline,
        val run: TimelineRunState,
        val local: TimelineLocalState,
        val approvals: TimelineHostApprovals,
    )

    private companion object {
        const val LOAD_FAILED = "Could not load the conversation"
        const val SEND_FAILED = "Could not send the message"
        const val CONTROL_FAILED = TIMELINE_STOP_FAILED
        const val STOP_TRANSPORT = "timeline"

        /** The page's flows run only while it collects them, as desktop's port does. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** How long a query waits before it asks the host's remote search, as Android's chat does. */
        const val SEARCH_REMOTE_DEBOUNCE_MS = 180L
    }
}

/** [this] state with the port's own page fields of [fields]; the rest of [fields] stays unused. */
private fun ChatUiState.withPageFields(fields: ChatUiState): ChatUiState = copy(
    searchQuery = fields.searchQuery,
    isSearchActive = fields.isSearchActive,
    isSearching = fields.isSearching,
    searchResults = fields.searchResults,
    goalStatus = fields.goalStatus,
    isGoalStatusLoading = fields.isGoalStatusLoading,
)

private fun continueGoalPrompt(objective: String): String =
    "Continue working on the active goal: $objective. Take the next concrete step, and " +
        "update or complete the goal when appropriate."
