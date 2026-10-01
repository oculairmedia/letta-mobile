package com.letta.mobile.desktop.chat

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.desktop.buildModelOptions
import com.letta.mobile.desktop.desktopQueuedSendActions
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What the shell hands the port beyond the controller itself. */
internal data class DesktopChatSessionBindings(
    /** The shell's A2UI dispatch (resolves the conversation id, then sends over the channel transport). */
    val onA2uiAction: (A2uiAction) -> Unit = {},
    /** Persists a pinch/zoom font scale through the desktop font-scale host. */
    val onSetFontScale: (Float) -> Unit = {},
    /** Live read of whether the active gateway can answer approvals. */
    val canSubmitApprovals: () -> Boolean = { true },
    val maxAttachments: Int = AttachmentLimits.Default.maxAttachmentCount,
)

/**
 * letta-mobile-bglj6.1: desktop's [ChatSessionPort], an adapter over [DesktopChatController].
 *
 * The controller stays the conversation owner; this only maps its flows onto the shared page's
 * contracts (see DesktopChatSessionMapping.kt) and forwards [ChatActions] to it. Construct one per
 * controller and share it across every presentation, so the draft and the run are the same object
 * in each mode.
 */
internal class DesktopChatSessionPort(
    private val controller: DesktopChatController,
    scope: CoroutineScope,
    private val bindings: DesktopChatSessionBindings = DesktopChatSessionBindings(),
) : ChatSessionPort {
    private val hostInputs = MutableStateFlow(DesktopChatComposerHostInputs())
    private val localTimeline = MutableStateFlow(DesktopChatLocalTimelineState())

    /** Feeds the composer chrome the shell computes (commands, mentionables, context usage). */
    fun updateHostInputs(inputs: DesktopChatComposerHostInputs) {
        hostInputs.value = inputs
    }

    override val uiState: StateFlow<ChatUiState> = timelineInputs()
        .runningFold(null as ChatUiState?) { previous, inputs -> desktopChatUiState(inputs, previous) }
        .map { it ?: initialUiState() }
        .stateIn(scope, SharingStarted.Eagerly, initialUiState())

    override val composer: StateFlow<ChatComposerUiState> = composerInputs()
        .map(::desktopChatComposerUiState)
        .stateIn(scope, SharingStarted.Eagerly, desktopChatComposerUiState(currentComposerInputs()))

    override val actions: ChatActions = DesktopChatActions(
        controller = controller,
        bindings = bindings,
        commandsById = { hostInputs.value.commands.associateBy(ComposerCommand::label) },
        localTimeline = localTimeline,
    )

    /** Read live: approvals and the canonical (paged) route can change after construction. */
    override val capabilities: ChatSurfaceCapabilities
        get() = ChatSurfaceCapabilities(
            attachImages = true,
            rerun = false,
            approvals = bindings.canSubmitApprovals(),
            modelSwitch = true,
            workingDirectory = controller.supportsWorkingDirectory,
            search = false,
            pagedHistory = controller.canonicalPresentation.value != null,
            fontScale = true,
        )

    private fun initialUiState(): ChatUiState = desktopChatUiState(currentTimelineInputs(), previous = null)

    private fun currentTimelineInputs() = DesktopChatTimelineInputs(
        surface = controller.state.value,
        presence = controller.replyPresence.value,
        cancellingConversationId = controller.cancellingConversationId.value,
        sendQueue = ConversationSendQueue(),
        local = localTimeline.value,
    )

    private fun timelineInputs(): Flow<DesktopChatTimelineInputs> = combine(
        controller.state,
        controller.replyPresence,
        controller.cancellingConversationId,
        selectedSendQueue(),
        localTimeline,
    ) { surface, presence, cancelling, queue, local ->
        DesktopChatTimelineInputs(surface, presence, cancelling, queue, local)
    }

    /**
     * The selected conversation's queue, re-resolved whenever the selection or the canonical route
     * changes (the same trigger `rememberSelectedSendQueue` uses for the old page).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun selectedSendQueue(): Flow<ConversationSendQueue> = combine(
        controller.canonicalPresentation,
        controller.state.map { it.selectedConversationId }.distinctUntilChanged(),
    ) { _, selectedId -> selectedId }
        .flatMapLatest { selectedId ->
            val queues = controller.canonicalSendQueue()?.state
            if (selectedId == null || queues == null) {
                flowOf(ConversationSendQueue())
            } else {
                queues.map { it[QueueConversationId(selectedId)] ?: ConversationSendQueue() }
            }
        }
        .distinctUntilChanged()

    private fun currentComposerInputs() = DesktopChatComposerInputs(
        surface = controller.state.value,
        host = hostInputs.value,
        modelOptions = buildModelOptions(controller.availableModels.value),
        workingDirectory = DesktopWorkingDirectoryInputs(
            supported = controller.supportsWorkingDirectory,
            path = controller.selectedConversationWorkingDirectory.value,
            loading = controller.workingDirectoryLoading.value,
        ),
        canQueueWhileStreaming = controller.canonicalPresentation.value != null,
        maxAttachments = bindings.maxAttachments,
    )

    private fun composerInputs(): Flow<DesktopChatComposerInputs> = combine(
        controller.state,
        hostInputs,
        controller.availableModels,
        workingDirectoryInputs(),
        controller.canonicalPresentation,
    ) { surface, host, models, workingDirectory, canonical ->
        DesktopChatComposerInputs(
            surface = surface,
            host = host,
            modelOptions = buildModelOptions(models),
            workingDirectory = workingDirectory,
            canQueueWhileStreaming = canonical != null,
            maxAttachments = bindings.maxAttachments,
        )
    }

    private fun workingDirectoryInputs(): Flow<DesktopWorkingDirectoryInputs> = combine(
        controller.selectedConversationWorkingDirectory,
        controller.workingDirectoryLoading,
    ) { path, loading ->
        DesktopWorkingDirectoryInputs(supported = controller.supportsWorkingDirectory, path = path, loading = loading)
    }
}

/** [ChatActions] forwarded to [DesktopChatController]; what desktop cannot do is a no-op. */
internal class DesktopChatActions(
    private val controller: DesktopChatController,
    private val bindings: DesktopChatSessionBindings,
    private val commandsById: () -> Map<String, ComposerCommand>,
    private val localTimeline: MutableStateFlow<DesktopChatLocalTimelineState>,
) : ChatActions {
    private val queue = desktopQueuedSendActions(controller)

    private val selectedConversationId: String?
        get() = controller.state.value.selectedConversationId

    override fun updateComposerText(text: String) = controller.updateComposerText(text)

    override fun send() = controller.send()

    /**
     * Desktop has no draft-free send path, so this sends [text] through the composer and puts the
     * user's unfinished text back afterwards. Staged images go with it.
     */
    override fun sendText(text: String) {
        val draft = controller.state.value.composerText
        controller.updateComposerText(text)
        controller.send()
        if (draft.isNotBlank() && controller.state.value.composerText.isEmpty()) {
            controller.updateComposerText(draft)
        }
    }

    override fun attachImage(image: MessageContentPart.Image) = controller.attachImage(image)

    override fun removeAttachment(index: Int) = controller.removeImageAttachment(index)

    override fun reportComposerError(message: String) = controller.showComposerError(message)

    /** Desktop's composer error is the surface error; it clears on the next attach/remove/send. */
    override fun clearComposerError() = Unit

    override fun runComposerCommand(command: ChatComposerCommand) {
        commandsById()[command.id]?.run?.invoke()
    }

    override fun uninstallComposerCommand(command: ChatComposerCommand) = Unit

    override fun stopRun() {
        selectedConversationId?.let(controller::stopActiveRun)
    }

    override fun rerun(message: UiMessage) = Unit

    override fun submitApproval(requestId: String, toolCallIds: List<String>, approve: Boolean, reason: String?) {
        if (bindings.canSubmitApprovals()) controller.submitApproval(requestId, toolCallIds, approve, reason)
    }

    override fun submitA2uiAction(action: A2uiAction) = bindings.onA2uiAction(action)

    override fun dismissA2uiSurface(surfaceId: String) = Unit

    override fun markA2uiSnackbarShown(id: Long) = Unit

    override fun cancelQueuedSend(id: QueuedSendId) = queue.onCancel(id)

    override fun sendQueuedNow(id: QueuedSendId) = queue.onSendNow(id)

    override fun resumeSendQueue() = queue.onResume()

    override fun toggleRunCollapsed(runId: String) = localTimeline.update { it.toggleRun(runId) }

    override fun toggleReasoningExpanded(messageId: String) = localTimeline.update { it.toggleReasoning(messageId) }

    /** The canonical paged timeline pages itself; the legacy list has no older-history window. */
    override fun loadOlderMessages() = Unit

    override fun releaseOlderMessages() = Unit

    override fun expandTruncatedToolResult(messageId: String) = Unit

    override fun retryLoad() = controller.retryConnection()

    override fun clearError() = Unit

    override fun setFontScale(scale: Float) = bindings.onSetFontScale(scale)

    /** Desktop's model switch takes a selection value only; the effort is chosen elsewhere. */
    override fun selectModel(handle: String, effort: ReasoningEffortChoice) = controller.setConversationModel(handle)

    override fun changeWorkingDirectory(path: String) = controller.changeSelectedConversationWorkingDirectory(path)

    override fun updateSearchQuery(query: String) = Unit

    override fun clearSearch() = Unit
}
