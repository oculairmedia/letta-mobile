package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.withPendingApprovalDetails
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.CatalogModel
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.reasoningEffortsOf
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.toConversationState
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.ChatWorkingDirectoryUiState
import com.letta.mobile.ui.chat.session.TimelineHostApprovals
import com.letta.mobile.ui.chat.session.TimelineLocalState
import com.letta.mobile.ui.chat.session.TimelineRunState
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
    ).withHostApprovals(HOST_OWNED_RUN, inputs.approvals)
}

/** Desktop's controller owns every approval answer, so the shared overlay sees none of the port's own. */
private val HOST_OWNED_RUN = TimelineRunState()

/**
 * The page (snackbar) error: the shared rule over [local] (hidden once acknowledged, glow kept),
 * minus the composer's own error, which the composer shows next to the draft.
 */
internal fun DesktopChatSurfaceState.pageError(local: TimelineLocalState): String? =
    local.pageError(errorMessage).takeUnless { it == composerErrorMessage }

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

/** Everything the composer state is derived from, at one instant. */
internal data class DesktopChatComposerInputs(
    val surface: DesktopChatSurfaceState,
    val host: DesktopChatComposerHostInputs,
    /** Model picker options as (label, selection value), the shape `buildModelOptions` returns. */
    val modelOptions: List<Pair<String, String>>,
    /** letta-mobile-by4vm.85: the selected conversation's effort and each option's efforts. */
    val modelEfforts: DesktopModelEfforts = DesktopModelEfforts(),
    /** letta-mobile-by4vm.98: the shared working-directory chip; null where the gateway has none. */
    val workingDirectory: ChatWorkingDirectoryUiState?,
    val canQueueWhileStreaming: Boolean,
    val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
    val permissionMode: ChatPermissionModeUiState? = null,
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
        model = desktopChatModelUiState(surface.composerModelLabel, inputs.modelOptions, inputs.modelEfforts),
        contextUsage = inputs.host.contextUsage,
        workingDirectory = inputs.workingDirectory,
        permissionMode = inputs.permissionMode,
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
 * letta-mobile-by4vm.85: the reasoning efforts the composer offers. [byValue] holds each option's
 * efforts by selection value (empty where the gateway cannot send an effort, so no effort chip
 * shows); [current] is the selected conversation's effort, this session's pick else the host's.
 */
internal data class DesktopModelEfforts(
    val byValue: Map<String, List<String>> = emptyMap(),
    val current: String? = null,
)

/** Everything [desktopModelEfforts] reads, at one instant. */
internal data class DesktopModelEffortInputs(
    val models: List<LlmModel>,
    /** The host catalog's rows (`model.list`), which advertise each model's efforts. */
    val catalog: List<CatalogModel>,
    val choices: ConversationModelChoices,
    val selectedConversationId: String?,
    /** False where the gateway cannot send an effort with a switch: no option offers one then. */
    val supported: Boolean,
)

/** letta-mobile-by4vm.85: each option's efforts (by selection value) and the conversation's effort. */
internal fun desktopModelEfforts(inputs: DesktopModelEffortInputs): DesktopModelEfforts {
    if (!inputs.supported) return DesktopModelEfforts()
    val byValue = inputs.models.mapNotNull { model ->
        reasoningEffortsOf(inputs.catalog, model).takeIf { it.isNotEmpty() }
            ?.let { ModelCatalog.selectionValue(inputs.models, model) to it }
    }.toMap()
    return DesktopModelEfforts(byValue = byValue, current = inputs.selectedConversationId?.let(inputs.choices.efforts::get))
}

/**
 * The composer model chip. Desktop stores either a display label or (after a switch) the selection
 * value in `composerModelLabel`, so the current option is matched on either.
 */
internal fun desktopChatModelUiState(
    composerModelLabel: String,
    modelOptions: List<Pair<String, String>>,
    efforts: DesktopModelEfforts = DesktopModelEfforts(),
): ChatModelUiState {
    val options = modelOptions.map { (label, value) ->
        ChatModelOption(handle = value, label = label, reasoningEfforts = efforts.byValue[value].orEmpty().toImmutableList())
    }
    val current = options.firstOrNull { it.handle == composerModelLabel || it.label == composerModelLabel }
    return ChatModelUiState(
        currentHandle = current?.handle,
        currentLabel = current?.label ?: composerModelLabel,
        currentEffort = efforts.current,
        options = options.toImmutableList(),
    )
}
