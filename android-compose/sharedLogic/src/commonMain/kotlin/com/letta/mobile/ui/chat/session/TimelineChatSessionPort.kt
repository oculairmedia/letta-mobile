package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.ui.chat.render.ChatTimelinePresenter
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** A user's answer to an approval request, as the page raises it. */
data class ChatApprovalAnswer(
    val requestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String?,
)

/**
 * The run controls the owner's transport offers. Null means the transport cannot, and the port
 * reports that through [ChatSurfaceCapabilities] so the page hides the control.
 */
data class TimelineChatRunControls(
    /** Asks the server to stop the conversation's active run; true when it confirmed. */
    val stopRun: (suspend () -> Boolean)? = null,
    val answerApproval: (suspend (ChatApprovalAnswer) -> Unit)? = null,
)

/** What the port is built from besides the session itself. */
data class TimelineChatSessionOptions(
    val controls: TimelineChatRunControls = TimelineChatRunControls(),
    val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
    val placeholder: String? = null,
)

/**
 * letta-mobile-o4ygk.4.5: a [ChatSessionPort] owned entirely in commonMain, over one conversation's
 * shared timeline loop.
 *
 * The timeline is projected by the same [ChatTimelinePresenter] Android and desktop use (rows, the
 * list-change hint, streaming and typing presence), the composer runs on [ChatComposerPolicy], and
 * sends go through the loop's own optimistic-send pipeline. A platform binds it by handing over a
 * [TimelineChatSession] and the run controls its transport has; the web client is the first host
 * that has no conversation owner of its own.
 *
 * Capabilities it does not have yet (model switch, working directory, search, paged history, goals,
 * re-run, the send queue) are reported off, so the page hides them.
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

    override val uiState: StateFlow<ChatUiState> = timelineInputs()
        .runningFold(null as ChatUiState?) { previous, inputs -> present(inputs, previous) }
        .map { it ?: initialUiState() }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialUiState())

    override val composer: StateFlow<ChatComposerUiState> = combine(draft, uiState) { current, ui ->
        timelineComposerUiState(current, ui.isRunInFlight, options)
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), timelineComposerUiState(draft.value, false, options))

    override val capabilities: StateFlow<ChatSurfaceCapabilities> =
        MutableStateFlow(timelineCapabilities(options.controls)).asStateFlow()

    override val actions: ChatActions = TimelineChatActions(this)

    init {
        scope.launch { session.events.collect(::onSyncEvent) }
        scope.launch { load() }
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
        combine(session.timeline, run, local) { timeline, runState, localState ->
            TimelineInputs(timeline, runState, localState)
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
        val presentation = presenter.present(projection, IdlePresenceSignals, prior.isStreaming, prior.isAgentTyping)
        return timelineChatUiState(target, presentation, inputs.run, inputs.local)
    }

    private fun initialUiState(): ChatUiState = ChatUiState(agentName = target.agentName, agentId = target.agentId)

    internal fun updateDraft(transform: (TimelineComposerDraft) -> TimelineComposerDraft) = draft.update(transform)

    internal fun updateLocal(transform: (TimelineLocalState) -> TimelineLocalState) = local.update(transform)

    internal fun updateRun(transform: (TimelineRunState) -> TimelineRunState) = run.update(transform)

    internal fun sendDraft() {
        if (uiState.value.isRunInFlight) return
        val sendDraft = ChatComposerPolicy.beginSend(draft.value.state) ?: return
        draft.value = TimelineComposerDraft(sendDraft.nextState)
        launchSend(TimelineSend(sendDraft.text, sendDraft.attachments))
    }

    /** Sends [send] without touching the draft (starter prompts, "send again"). */
    internal fun sendDirect(send: TimelineSend) {
        if (send.text.isBlank() || uiState.value.isRunInFlight) return
        launchSend(send)
    }

    private fun launchSend(send: TimelineSend) {
        // A new run starts clean: no earlier error, and no stop request left over from the last run.
        run.update { it.copy(error = null, acknowledgedError = null, cancelling = false) }
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

    internal fun stopRun() {
        val stop = options.controls.stopRun ?: return
        if (!uiState.value.isRunInFlight) return
        run.update { it.copy(cancelling = true) }
        scope.launch { runControl { if (!stop()) run.update { state -> state.copy(cancelling = false) } } }
    }

    internal fun answerApproval(answer: ChatApprovalAnswer) {
        val submit = options.controls.answerApproval ?: return
        if (answer.requestId in run.value.submittingApprovals) return
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

    private suspend fun runControl(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            run.update { it.copy(error = failure.message ?: CONTROL_FAILED, cancelling = false) }
        }
    }

    internal val currentRun: TimelineRunState get() = run.value

    internal val attachmentLimits: AttachmentLimits get() = options.attachmentLimits

    private data class TimelineInputs(
        val timeline: Timeline,
        val run: TimelineRunState,
        val local: TimelineLocalState,
    )

    private companion object {
        const val LOAD_FAILED = "Could not load the conversation"
        const val SEND_FAILED = "Could not send the message"
        const val CONTROL_FAILED = "The server did not accept that"

        /** The page's flows run only while it collects them, as desktop's port does. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
