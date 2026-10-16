package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.projection.withPendingApprovalDetails
import com.letta.mobile.data.chat.projection.withQueuedApprovals
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatComposerState
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.ui.chat.render.ChatPresenceSignals
import com.letta.mobile.ui.chat.render.ChatPresentation
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * letta-mobile-o4ygk.4.5: the pure state of a TimelineChatSessionPort and its mapping onto the
 * shared page's contracts, kept free of flows so each rule is unit-testable on plain values.
 */

/** The composer draft: the shared composer state plus an error the host reported (a failed pick). */
data class TimelineComposerDraft(
    val state: ChatComposerState = ChatComposerState(),
    val hostError: String? = null,
) {
    fun withText(text: String) = copy(state = ChatComposerPolicy.updateText(state, text))

    fun withImage(image: MessageContentPart.Image, limits: AttachmentLimits = AttachmentLimits.Default) =
        copy(state = ChatComposerPolicy.attachImage(state, image, limits), hostError = null)

    fun withoutImage(index: Int) = copy(state = ChatComposerPolicy.removeImageAttachment(state, index), hostError = null)

    fun cleared() = copy(state = state.copy(error = null), hostError = null)
}

/** One message to send: its text and staged images. */
data class TimelineSend(
    val text: String,
    val attachments: List<MessageContentPart.Image>,
)

/** The run and load facts the timeline itself does not carry. */
data class TimelineRunState(
    val loading: Boolean = true,
    val loadError: String? = null,
    /** The last send or control failure; kept until the next send, so the glow reads "failed". */
    val error: String? = null,
    /** A stop was requested and the run has not ended yet. */
    val cancelling: Boolean = false,
    /**
     * letta-mobile-by4vm.81: Stop gave up on the run's terminal and dropped it locally; the page
     * shows the run idle until the timeline's own run ends or the next send.
     */
    val clearedLocally: Boolean = false,
    /** Approval request ids whose answer is in flight. */
    val submittingApprovals: Set<String> = emptySet(),
)

/**
 * Timeline view state a session port keeps for the page: collapsed runs, expanded reasoning, and the
 * page error already shown. letta-mobile-by4vm.98: desktop's port keeps the same state.
 */
data class TimelineLocalState(
    val collapsedRunIds: Set<String> = emptySet(),
    val expandedReasoningMessageIds: Set<String> = emptySet(),
    /** The page error the page already showed (its snackbar), so it is not shown twice. */
    val acknowledgedError: String? = null,
) {
    fun toggleRun(runId: String) = copy(collapsedRunIds = collapsedRunIds.toggle(runId))

    fun toggleReasoning(messageId: String) =
        copy(expandedReasoningMessageIds = expandedReasoningMessageIds.toggle(messageId))
}

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

/**
 * The timeline port's presence comes from the projection alone (a prompt still sending, a run still
 * open); it has no A2UI wait or transport turn flag of its own.
 */
internal val IdlePresenceSignals = ChatPresenceSignals(
    replyStreaming = false,
    a2uiThinkingActive = false,
    duplicateInitialMessageInFlight = false,
    turnInFlight = false,
)

/** The page's timeline state for one presentation of the conversation. */
fun timelineChatUiState(
    target: TimelineChatTarget,
    presentation: ChatPresentation,
    run: TimelineRunState,
    local: TimelineLocalState,
): ChatUiState = ChatUiState(
    conversationState = run.conversationState(target.conversationId),
    messages = presentation.messages,
    messageListChange = presentation.messageListChange,
    isLoadingMessages = run.loading,
    isStreaming = presentation.isStreaming,
    isAgentTyping = presentation.isAgentTyping,
    agentName = target.agentName,
    agentId = target.agentId,
    error = local.pageError(run.error),
    runFailed = run.error != null,
    collapsedRunIds = local.collapsedRunIds.toImmutableSet(),
    expandedReasoningMessageIds = local.expandedReasoningMessageIds.toImmutableSet(),
    isCancelling = run.cancelling,
    activeApprovalRequestId = answeringApprovalOnScreen(run.submittingApprovals, presentation.messages),
)

private fun TimelineRunState.conversationState(conversationId: String): ConversationState = when {
    loadError != null -> ConversationState.Error(loadError)
    loading -> ConversationState.Loading
    else -> ConversationState.Ready(conversationId)
}

/** letta-mobile-by4vm.79: the approval state a host hands the port through [TimelineChatSessionOptions.approvals]. */
data class TimelineHostApprovals(
    val submitting: Set<String> = emptySet(),
    val queued: Set<String> = emptySet(),
    val details: Map<String, PendingApprovalDetails> = emptyMap(),
)

/**
 * [this] with the host's approval state laid over it: parked details joined onto their rows, queued
 * answers marked, and the row answered by either the host or the port ([portSubmitting]) disabled.
 * Unchanged when the host reports nothing. letta-mobile-by4vm.93: on both clients this derives
 * [ChatUiState.activeApprovalRequestId]: the bottom-most row (parked rows included) whose request has
 * an answer in flight, null when none has a row; it clears once the request leaves the in-flight set
 * (delivery failed or cancelled, the timeline decided it, or a sibling parallel call was parked).
 */
fun ChatUiState.withHostApprovals(approvals: TimelineHostApprovals, portSubmitting: Set<String> = emptySet()): ChatUiState {
    if (approvals == TimelineHostApprovals()) return this
    val joined = withPendingApprovalDetails(approvals.details).withQueuedApprovals(approvals.queued)
    val answering = answeringApprovalOnScreen(portSubmitting + approvals.submitting, joined.messages)
    return if (answering == joined.activeApprovalRequestId) joined else joined.copy(activeApprovalRequestId = answering)
}

/** The newest on-screen approval whose answer is in flight; the page disables that row's buttons. */
internal fun answeringApprovalOnScreen(submitting: Set<String>, messages: List<UiMessage>): String? {
    if (submitting.isEmpty()) return null
    return messages.asReversed().firstNotNullOfOrNull { message ->
        message.approvalRequest?.requestId?.takeIf { it in submitting }
    }
}

/** Whether a send may go out now: none while a run is in flight, unless the host queues it. */
data class TimelineSendGate(val runInFlight: Boolean, val canQueue: Boolean) {
    val blocked: Boolean get() = runInFlight && !canQueue
}

/** The composer for [draft]; a send during a run waits for it to end unless [gate] queues it. */
fun timelineComposerUiState(
    draft: TimelineComposerDraft,
    gate: TimelineSendGate,
    options: TimelineChatSessionOptions,
): ChatComposerUiState = ChatComposerUiState(
    text = draft.state.text,
    attachments = draft.state.pendingImageAttachments.toImmutableList(),
    error = draft.hostError ?: draft.state.error?.message(options.attachmentLimits),
    canSend = draft.state.hasPayload && !gate.blocked,
    canQueueWhileStreaming = gate.canQueue,
    maxAttachments = options.attachmentLimits.maxAttachmentCount,
    attachmentLimits = options.attachmentLimits,
)

/** The composer's words for a rejected attachment. */
fun ChatComposerError.message(limits: AttachmentLimits): String = when (this) {
    ChatComposerError.MaxAttachmentCountExceeded -> "Attach up to ${limits.maxAttachmentCount} images."
    ChatComposerError.MaxTotalBase64BytesExceeded -> "Attached images exceed the payload limit."
    ChatComposerError.AttachmentLoadFailed -> "Could not attach image."
}

/** What a timeline port can do: images, the controls its transport offers, and the host's extensions. */
fun timelineCapabilities(controls: TimelineChatRunControls) = ChatSurfaceCapabilities(
    attachImages = true,
    rerun = false,
    approvals = controls.answerApproval != null,
    modelSwitch = false,
    workingDirectory = false,
    search = false,
    pagedHistory = false,
    fontScale = false,
    goals = false,
)

/** The production [TimelineChatSession]: the shared [TimelineSyncLoop] for one conversation. */
class TimelineSyncLoopSession(
    private val loop: TimelineSyncLoop,
    private val hydrateLimit: Int = DEFAULT_HYDRATE_LIMIT,
) : TimelineChatSession {
    override val timeline: StateFlow<Timeline> get() = loop.state
    override val events: SharedFlow<TimelineSyncEvent> get() = loop.events

    override suspend fun hydrate() = loop.hydrate(limit = hydrateLimit)

    override suspend fun send(text: String, attachments: List<MessageContentPart.Image>) {
        loop.send(text, attachments)
    }

    private companion object {
        const val DEFAULT_HYDRATE_LIMIT = 50
    }
}
