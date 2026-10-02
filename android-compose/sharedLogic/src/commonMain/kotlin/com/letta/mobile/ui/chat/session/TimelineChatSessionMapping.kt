package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatComposerState
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
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
    /** The [error] the page already showed, so it is not shown twice. */
    val acknowledgedError: String? = null,
    /** A stop was requested and the run has not ended yet. */
    val cancelling: Boolean = false,
    /** Approval request ids whose answer is in flight. */
    val submittingApprovals: Set<String> = emptySet(),
)

/** Timeline view state the page toggles: collapsed runs and expanded reasoning. */
data class TimelineLocalState(
    val collapsedRunIds: Set<String> = emptySet(),
    val expandedReasoningMessageIds: Set<String> = emptySet(),
) {
    fun toggleRun(runId: String) = copy(collapsedRunIds = collapsedRunIds.toggle(runId))

    fun toggleReasoning(messageId: String) =
        copy(expandedReasoningMessageIds = expandedReasoningMessageIds.toggle(messageId))
}

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

/**
 * The timeline port's presence comes from the projection alone (a prompt still sending, a run still
 * open); it has no client-mode stream, A2UI wait or transport turn flag of its own.
 */
internal val IdlePresenceSignals = ChatPresenceSignals(
    replyStreaming = false,
    clientModeStreamInFlight = false,
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
    error = run.error.takeUnless { it == run.acknowledgedError },
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

/** The newest on-screen approval whose answer is in flight; the page disables that row's buttons. */
internal fun answeringApprovalOnScreen(submitting: Set<String>, messages: List<UiMessage>): String? {
    if (submitting.isEmpty()) return null
    return messages.asReversed().firstNotNullOfOrNull { message ->
        message.approvalRequest?.requestId?.takeIf { it in submitting }
    }
}

/** The composer for [draft]; a send waits for the run to end (this port has no send queue). */
fun timelineComposerUiState(
    draft: TimelineComposerDraft,
    runInFlight: Boolean,
    options: TimelineChatSessionOptions,
): ChatComposerUiState = ChatComposerUiState(
    text = draft.state.text,
    attachments = draft.state.pendingImageAttachments.toImmutableList(),
    error = draft.hostError ?: draft.state.error?.message(options.attachmentLimits),
    canSend = draft.state.hasPayload && !runInFlight,
    canQueueWhileStreaming = false,
    placeholder = options.placeholder,
    maxAttachments = options.attachmentLimits.maxAttachmentCount,
    attachmentLimits = options.attachmentLimits,
)

/** The composer's words for a rejected attachment. */
fun ChatComposerError.message(limits: AttachmentLimits): String = when (this) {
    ChatComposerError.MaxAttachmentCountExceeded -> "Attach up to ${limits.maxAttachmentCount} images."
    ChatComposerError.MaxTotalBase64BytesExceeded -> "Attached images exceed the payload limit."
    ChatComposerError.AttachmentLoadFailed -> "Could not attach image."
}

/** What a timeline port can do: images and the controls its transport offers; nothing else yet. */
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
