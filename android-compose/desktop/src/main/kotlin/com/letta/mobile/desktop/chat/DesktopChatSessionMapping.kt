package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.withPendingApprovalDetails
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.toConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.TimelineHostApprovals
import com.letta.mobile.ui.chat.session.TimelineLocalState
import com.letta.mobile.ui.chat.session.pageError
import com.letta.mobile.ui.chat.session.withHostApprovals
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/*
 * letta-mobile-bglj6.1: pure mapping from desktop's chat state onto the shared chat page's
 * contracts (ChatUiState for the timeline, ChatComposerUiState for the composer). Kept free of
 * flows and the controller so each rule is unit-testable on plain values.
 */

/** Everything the timeline state is derived from, at one instant. */
internal data class DesktopChatTimelineInputs(
    val surface: DesktopChatSurfaceState,
    /** The SELECTED conversation's presence, as the controller derives it. */
    val presence: ChatStreamingPresence,
    val cancellingConversationId: String?,
    val sendQueue: ConversationSendQueue,
    /** letta-mobile-by4vm.98: the shared port-local view state (toggles, the acknowledged error). */
    val local: TimelineLocalState,
    /**
     * letta-mobile-by4vm.97: the controller's approval state as the shared overlay reads it: answers
     * in flight, queued offline (bzvro.14), and each parked control request's details (bzvro.11/.12).
     */
    val approvals: TimelineHostApprovals = TimelineHostApprovals(),
)

/**
 * The shared page's timeline state for [inputs]. [previous] is the last emitted state, used to
 * classify the message-list change (and to keep the same list instance when nothing changed).
 */
internal fun desktopChatUiState(inputs: DesktopChatTimelineInputs, previous: ChatUiState?): ChatUiState {
    val surface = inputs.surface
    // Parked details join before the diff, so a join reads as the change it is rather than a
    // full one; the overlay below then finds every row current and keeps the list as it is.
    val messages = nextMessages(
        previous?.messages,
        withPendingApprovalDetails(surface.selectedMessages, inputs.approvals.details),
    )
    val selected = surface.selectedConversation
    return ChatUiState(
        conversationState = surface.runtimeState.toConversationState(),
        messages = messages,
        messageListChange = ChatMessageListChange.compute(previous?.messages.orEmpty(), messages),
        isLoadingMessages = surface.isLoading,
        isStreaming = inputs.presence.isStreaming,
        isAgentTyping = inputs.presence.isAgentTyping,
        agentName = selected?.agentName.orEmpty(),
        agentId = selected?.agentId,
        // A composer error is the composer's to show (next to the draft), not the page's.
        error = surface.pageError(inputs.local),
        // The controller keeps its error until the next send; the glow reads it, shown or not,
        // as the host's own page background does (rememberDesktopAmbientStatus).
        runFailed = surface.errorMessage != null,
        collapsedRunIds = inputs.local.collapsedRunIds.toImmutableSet(),
        expandedReasoningMessageIds = inputs.local.expandedReasoningMessageIds.toImmutableSet(),
        isCancelling = surface.selectedConversationId != null &&
            inputs.cancellingConversationId == surface.selectedConversationId,
        sendQueue = inputs.sendQueue,
    ).withHostApprovals(inputs.approvals) // desktop's controller owns every answer; the port has none
}

/**
 * The page (snackbar) error: the shared rule over [local] (hidden once acknowledged, glow kept),
 * minus the composer's own error, which the composer shows next to the draft.
 */
internal fun DesktopChatSurfaceState.pageError(local: TimelineLocalState): String? =
    local.pageError(errorMessage).takeUnless { it == composerErrorMessage }

/** Reuses the previous list instance when the content is unchanged, so the timeline skips work. */
private fun nextMessages(previous: ImmutableList<UiMessage>?, next: List<UiMessage>): ImmutableList<UiMessage> =
    if (previous != null && previous == next) previous else next.toImmutableList()

/**
 * The controller's draft as the composer shows it; the extension points' chrome (chips, queue,
 * host commands) is laid over it by [com.letta.mobile.ui.chat.session.withChrome].
 */
internal fun DesktopChatSurfaceState.composerUiState(limits: AttachmentLimits): ChatComposerUiState = ChatComposerUiState(
    text = composerText,
    attachments = pendingImageAttachments.toImmutableList(),
    error = composerErrorMessage,
    canSend = canSend,
    maxAttachments = limits.maxAttachmentCount,
    attachmentLimits = limits,
)
