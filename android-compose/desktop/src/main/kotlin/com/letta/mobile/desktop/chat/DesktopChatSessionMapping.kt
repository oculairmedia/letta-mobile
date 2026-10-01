package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.toConversationState
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatWorkingDirectoryUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/*
 * letta-mobile-bglj6.1: pure mapping from desktop's chat state onto the shared chat page's
 * contracts (ChatUiState for the timeline, ChatComposerUiState for the composer). Kept free of
 * flows and the controller so each rule is unit-testable on plain values.
 */

/** Timeline UI state desktop keeps locally (the old page held it in composable state). */
internal data class DesktopChatLocalTimelineState(
    val collapsedRunIds: Set<String> = emptySet(),
    val expandedReasoningMessageIds: Set<String> = emptySet(),
    /** The page error the shared page has already shown, so it is not shown again. */
    val acknowledgedError: String? = null,
) {
    fun toggleRun(runId: String) = copy(collapsedRunIds = collapsedRunIds.toggle(runId))

    fun toggleReasoning(messageId: String) =
        copy(expandedReasoningMessageIds = expandedReasoningMessageIds.toggle(messageId))
}

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

/** Everything the timeline state is derived from, at one instant. */
internal data class DesktopChatTimelineInputs(
    val surface: DesktopChatSurfaceState,
    /** The SELECTED conversation's presence, as the controller derives it. */
    val presence: ChatStreamingPresence,
    val cancellingConversationId: String?,
    val sendQueue: ConversationSendQueue,
    val local: DesktopChatLocalTimelineState,
    /** Approval request ids whose answer is in flight (the controller's submittingApprovals). */
    val submittingApprovals: Set<String> = emptySet(),
)

/**
 * The shared page's timeline state for [inputs]. [previous] is the last emitted state, used to
 * classify the message-list change (and to keep the same list instance when nothing changed).
 */
internal fun desktopChatUiState(inputs: DesktopChatTimelineInputs, previous: ChatUiState?): ChatUiState {
    val surface = inputs.surface
    val messages = nextMessages(previous?.messages, surface.selectedMessages)
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
        error = surface.pageError(inputs.local.acknowledgedError),
        collapsedRunIds = inputs.local.collapsedRunIds.toImmutableSet(),
        expandedReasoningMessageIds = inputs.local.expandedReasoningMessageIds.toImmutableSet(),
        isCancelling = surface.selectedConversationId != null &&
            inputs.cancellingConversationId == surface.selectedConversationId,
        sendQueue = inputs.sendQueue,
        activeApprovalRequestId = submittingApprovalOnScreen(inputs.submittingApprovals, messages),
    )
}

/**
 * The approval being answered, so its row disables its buttons until the answer lands. The page
 * tracks one; the newest on-screen request in flight is the one the user can still press.
 */
internal fun submittingApprovalOnScreen(submitting: Set<String>, messages: List<UiMessage>): String? {
    if (submitting.isEmpty()) return null
    return messages.asReversed().firstNotNullOfOrNull { message ->
        message.approvalRequest?.requestId?.takeIf { it in submitting }
    }
}

/**
 * The page (snackbar) error: the surface error unless it is the composer's own error, which the
 * composer shows, or the page already showed it ([acknowledged]). Acknowledging does not clear
 * the controller's error, so the ambient glow keeps reading "failed" until the next send.
 */
internal fun DesktopChatSurfaceState.pageError(acknowledged: String?): String? =
    errorMessage.takeUnless { it == composerErrorMessage || it == acknowledged }

/** Reuses the previous list instance when the content is unchanged, so the timeline skips work. */
private fun nextMessages(previous: ImmutableList<UiMessage>?, next: List<UiMessage>): ImmutableList<UiMessage> =
    if (previous != null && previous == next) previous else next.toImmutableList()

/** Composer chrome the shell computes from state outside the controller. */
internal data class DesktopChatComposerHostInputs(
    val commands: List<ComposerCommand> = emptyList(),
    val mentionables: List<Mentionable> = emptyList(),
    val contextUsage: ContextWindowUsageState? = null,
    val placeholder: String? = null,
)

/** The controller's working-directory facts, for gateways that have one. */
internal data class DesktopWorkingDirectoryInputs(
    val supported: Boolean,
    val path: String?,
    val loading: Boolean,
)

/** Everything the composer state is derived from, at one instant. */
internal data class DesktopChatComposerInputs(
    val surface: DesktopChatSurfaceState,
    val host: DesktopChatComposerHostInputs,
    /** Model picker options as (label, selection value), the shape `buildModelOptions` returns. */
    val modelOptions: List<Pair<String, String>>,
    val workingDirectory: DesktopWorkingDirectoryInputs,
    val canQueueWhileStreaming: Boolean,
    val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
)

internal fun desktopChatComposerUiState(inputs: DesktopChatComposerInputs): ChatComposerUiState {
    val surface = inputs.surface
    return ChatComposerUiState(
        text = surface.composerText,
        attachments = surface.pendingImageAttachments.toImmutableList(),
        error = surface.composerErrorMessage,
        canSend = surface.canSend,
        canQueueWhileStreaming = inputs.canQueueWhileStreaming,
        placeholder = inputs.host.placeholder,
        maxAttachments = inputs.attachmentLimits.maxAttachmentCount,
        attachmentLimits = inputs.attachmentLimits,
        commands = inputs.host.commands.map(ComposerCommand::toChatComposerCommand).toImmutableList(),
        mentionables = inputs.host.mentionables.toImmutableList(),
        model = desktopChatModelUiState(surface.composerModelLabel, inputs.modelOptions),
        contextUsage = inputs.host.contextUsage,
        workingDirectory = inputs.workingDirectory.toUiState(),
    )
}

/** Desktop commands are keyed by label: it is what the palette shows and what is unique there. */
internal fun ComposerCommand.toChatComposerCommand(): ChatComposerCommand =
    ChatComposerCommand(
        id = label,
        label = label,
        description = description,
        fillsComposer = fillsComposer,
    )

/**
 * The composer model chip. Desktop stores either a display label or (after a switch) the selection
 * value in `composerModelLabel`, so the current option is matched on either.
 */
internal fun desktopChatModelUiState(
    composerModelLabel: String,
    modelOptions: List<Pair<String, String>>,
): ChatModelUiState {
    val options = modelOptions.map { (label, value) -> ChatModelOption(handle = value, label = label) }
    val current = options.firstOrNull { it.handle == composerModelLabel || it.label == composerModelLabel }
    return ChatModelUiState(
        currentHandle = current?.handle,
        currentLabel = current?.label ?: composerModelLabel,
        options = options.toImmutableList(),
    )
}

private fun DesktopWorkingDirectoryInputs.toUiState(): ChatWorkingDirectoryUiState? =
    if (supported) ChatWorkingDirectoryUiState(path = path, isLoading = loading) else null
