package com.letta.mobile.desktop.chat

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.desktop.buildModelOptions
import com.letta.mobile.desktop.desktopQueuedSendActions
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.A2uiSnackbarId
import com.letta.mobile.ui.chat.session.A2uiSurfaceId
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.ui.chat.session.ChatModelHandle
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.ChatRunId
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatWorkingDirectory
import com.letta.mobile.ui.chat.session.toUiState
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the shell hands the port beyond the controller itself. */
internal data class DesktopChatSessionBindings(
    /** The shell's A2UI dispatch (resolves the conversation id, then sends over the channel transport). */
    val onA2uiAction: (A2uiAction) -> Unit = {},
    /** Persists a pinch/zoom font scale through the desktop font-scale host. */
    val onSetFontScale: (Float) -> Unit = {},
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
    private val scope: CoroutineScope,
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
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialUiState())

    override val composer: StateFlow<ChatComposerUiState> = composerInputs()
        .map(::desktopChatComposerUiState)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), desktopChatComposerUiState(currentComposerInputs()))

    /** letta-mobile-bzvro.7: the selected conversation's live status, from the window's run registry. */
    override val liveStatus: StateFlow<RuntimeLiveStatus> = combine(
        controller.runs,
        controller.state.map { it.selectedConversationId }.distinctUntilChanged(),
    ) { runs, selected -> selected?.let(runs::get)?.live ?: RuntimeLiveStatus.Idle }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RuntimeLiveStatus.Idle)

    override val actions: ChatActions = DesktopChatActions(
        controller = controller,
        bindings = bindings,
        commandsById = { hostInputs.value.commands.associateBy(ComposerCommand::label) },
        localTimeline = localTimeline,
        branchView = { uiState.value.let { DesktopBranchView(it.messages, it.hasMoreOlderMessages) } },
        onSetPermissionMode = ::requestPermissionMode,
    )

    /**
     * Follows the controller: approval support and the working directory follow the active
     * gateway, and paged history the canonical (paged) route, all of which change after
     * construction. The gateway has no flow of its own, so a state change re-reads it.
     */
    override val capabilities: StateFlow<ChatSurfaceCapabilities> = combine(
        controller.canSubmitApprovals,
        controller.canonicalPresentation,
        controller.state.map { controller.supportsWorkingDirectory }.distinctUntilChanged(),
        controller.state.map { controller.conversationManagement.supportsBranching }.distinctUntilChanged(),
    ) { approvals, canonical, workingDirectory, branching ->
        desktopCapabilities(
            DesktopCapabilityFacts(approvals, paged = canonical != null, workingDirectory = workingDirectory, branching = branching),
        )
    }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), currentCapabilities())

    private fun currentCapabilities(): ChatSurfaceCapabilities = desktopCapabilities(
        DesktopCapabilityFacts(
            approvals = controller.canSubmitApprovals.value,
            paged = controller.canonicalPresentation.value != null,
            workingDirectory = controller.supportsWorkingDirectory,
            branching = controller.conversationManagement.supportsBranching,
        ),
    )

    private fun initialUiState(): ChatUiState = desktopChatUiState(currentTimelineInputs(), previous = null)

    private fun currentTimelineInputs() = DesktopChatTimelineInputs(
        surface = controller.state.value,
        presence = controller.replyPresence.value,
        cancellingConversationId = controller.cancellingConversationId.value,
        sendQueue = ConversationSendQueue(),
        local = localTimeline.value,
        submittingApprovals = controller.submittingApprovals.value,
        approvalDetails = controller.pendingApprovalDetails.value,
    )

    private fun timelineInputs(): Flow<DesktopChatTimelineInputs> = combine(
        controller.state,
        controller.replyPresence,
        controller.cancellingConversationId,
        selectedSendQueue(),
        combine(localTimeline, controller.submittingApprovals, controller.pendingApprovalDetails, ::Triple),
    ) { surface, presence, cancelling, queue, (local, submitting, details) ->
        DesktopChatTimelineInputs(surface, presence, cancelling, queue, local, submitting, details)
    }.onEach { inputs ->
        // Once the error is gone (a send cleared it), the same message later is a new error.
        if (inputs.surface.errorMessage == null && inputs.local.acknowledgedError != null) {
            localTimeline.update { it.copy(acknowledgedError = null) }
        }
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
        attachmentLimits = controller.attachmentLimits,
    )

    private fun composerInputs(): Flow<DesktopChatComposerInputs> = combine(
        controller.state,
        hostInputs,
        controller.availableModels,
        combine(workingDirectoryInputs(), permissionModeUi(), ::Pair),
        controller.canonicalPresentation,
    ) { surface, host, models, (workingDirectory, permissionMode), canonical ->
        DesktopChatComposerInputs(
            surface = surface,
            host = host,
            modelOptions = buildModelOptions(models),
            workingDirectory = workingDirectory,
            canQueueWhileStreaming = canonical != null,
            attachmentLimits = controller.attachmentLimits,
            permissionMode = permissionMode,
        )
    }

    /**
     * letta-mobile-bzvro.13: the selected conversation's permission mode, for a gateway that runs the
     * turn engine; null (no chip) for one that cannot read or change it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun permissionModeUi(): Flow<ChatPermissionModeUiState?> =
        controller.state
            .map { state -> state.selectedConversation?.let { it.agentId?.takeIf(String::isNotBlank) to it.id } to controller.activeGateway }
            .distinctUntilChanged()
            .flatMapLatest { (target, gateway) ->
                val agentId = target?.first
                val modes = gateway as? DesktopPermissionModeController
                if (agentId == null || modes == null) {
                    flowOf(null)
                } else {
                    modes.permissionModes.observe(agentId, target.second).map { it.toUiState(modes.permissionModeUnavailableReason) }
                }
            }

    /** Asks the selected conversation's runtime for [mode]; the chip shows it pending until the server echoes it. */
    private fun requestPermissionMode(mode: AppServerPermissionMode) {
        val conversation = controller.state.value.selectedConversation ?: return
        val agentId = conversation.agentId?.takeIf(String::isNotBlank) ?: return
        val modes = controller.activeGateway as? DesktopPermissionModeController ?: return
        if (modes.permissionModeUnavailableReason != null) return
        scope.launch { modes.setPermissionMode(agentId, conversation.id, mode) }
    }

    private fun workingDirectoryInputs(): Flow<DesktopWorkingDirectoryInputs> = combine(
        controller.selectedConversationWorkingDirectory,
        controller.workingDirectoryLoading,
    ) { path, loading ->
        DesktopWorkingDirectoryInputs(supported = controller.supportsWorkingDirectory, path = path, loading = loading)
    }
}

/** What the controller supports right now: the inputs of [desktopCapabilities]. */
internal data class DesktopCapabilityFacts(
    /** The active gateway can answer approvals. */
    val approvals: Boolean,
    /** The selected conversation is on the canonical (paged) route. */
    val paged: Boolean,
    /** The active gateway can change a conversation's working directory. */
    val workingDirectory: Boolean,
    /** letta-mobile-bzvro.15/.16: the active gateway can fork (fork, edit and resend). */
    val branching: Boolean = false,
)

internal fun desktopCapabilities(facts: DesktopCapabilityFacts) = ChatSurfaceCapabilities(
    fork = facts.branching,
    editAndResend = facts.branching,
    attachImages = true,
    rerun = false,
    approvals = facts.approvals,
    modelSwitch = true,
    workingDirectory = facts.workingDirectory,
    search = false,
    pagedHistory = facts.paged,
    fontScale = true,
    goals = false,
)

/**
 * The port's flows run only while the page collects them, so a port left behind by a controller
 * change stops on its own instead of needing a scope of its own to cancel.
 */
private const val STOP_TIMEOUT_MS = 5_000L

/** [ChatActions] forwarded to [DesktopChatController]; what desktop cannot do is a no-op. */
internal class DesktopChatActions(
    private val controller: DesktopChatController,
    private val bindings: DesktopChatSessionBindings,
    private val commandsById: () -> Map<String, ComposerCommand>,
    private val localTimeline: MutableStateFlow<DesktopChatLocalTimelineState>,
    /** The page's timeline now, which a fork or edit plans against. */
    private val branchView: () -> DesktopBranchView = { DesktopBranchView(emptyList(), hasOlderMessages = true) },
    private val onSetPermissionMode: (AppServerPermissionMode) -> Unit = {},
) : ChatActions {
    private val queue = desktopQueuedSendActions(controller)

    private val selectedConversationId: String?
        get() = controller.state.value.selectedConversationId

    override fun updateComposerText(text: String) = controller.updateComposerText(text)

    override fun send() = controller.send()

    /** A draft-free send: the user's draft and staged images stay exactly as they are. */
    override fun sendText(text: String) = controller.sendText(text)

    override fun attachImage(image: MessageContentPart.Image) = controller.attachImage(image)

    override fun removeAttachment(index: Int) = controller.removeImageAttachment(index)

    override fun reportComposerError(message: String) = controller.showComposerError(message)

    override fun clearComposerError() = controller.clearComposerError()

    override fun runComposerCommand(command: ChatComposerCommand) {
        commandsById()[command.id]?.run?.invoke()
    }

    override fun uninstallComposerCommand(command: ChatComposerCommand) = Unit

    override fun stopRun() {
        selectedConversationId?.let(controller::stopActiveRun)
    }

    override fun rerun(message: UiMessage) = Unit

    override fun forkFromMessage(message: UiMessage) = controller.conversationManagement.forkFrom(message, branchView())

    override fun editAndResend(message: UiMessage) = controller.conversationManagement.editAndResend(message, branchView())

    override fun submitApproval(answer: ChatApprovalAnswer) {
        // A second press while the first answer is in flight must not answer twice.
        if (answer.requestId in controller.submittingApprovals.value) return
        if (controller.canSubmitApprovals.value) {
            controller.submitApproval(answer)
        }
    }

    override fun submitA2uiAction(action: A2uiAction) = bindings.onA2uiAction(action)

    override fun dismissA2uiSurface(surfaceId: A2uiSurfaceId) = Unit

    override fun markA2uiSnackbarShown(id: A2uiSnackbarId) = Unit

    override fun cancelQueuedSend(id: QueuedSendId) = queue.onCancel(id)

    override fun sendQueuedNow(id: QueuedSendId) = queue.onSendNow(id)

    override fun resumeSendQueue() = queue.onResume()

    override fun toggleRunCollapsed(runId: ChatRunId) = localTimeline.update { it.toggleRun(runId.value) }

    override fun toggleReasoningExpanded(messageId: ChatMessageId) = localTimeline.update { it.toggleReasoning(messageId.value) }

    /** The canonical paged timeline pages itself; the legacy list has no older-history window. */
    override fun loadOlderMessages() = Unit

    override fun releaseOlderMessages() = Unit

    override fun expandTruncatedToolResult(messageId: ChatMessageId) = Unit

    override fun retryLoad() = controller.retryConnection()

    /**
     * The page showed the error in its snackbar: acknowledge it so it is not shown again, but
     * leave the controller's error in place so the ambient glow stays "failed" until the next send.
     */
    override fun clearError() {
        val shown = controller.state.value.errorMessage ?: return
        localTimeline.update { it.copy(acknowledgedError = shown) }
    }

    override fun setFontScale(scale: Float) = bindings.onSetFontScale(scale)

    /** Desktop's model switch takes a selection value only; the effort is chosen elsewhere. */
    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) = controller.setConversationModel(handle.value)

    override fun setPermissionMode(mode: AppServerPermissionMode) = onSetPermissionMode(mode)

    override fun changeWorkingDirectory(directory: ChatWorkingDirectory) =
        controller.changeSelectedConversationWorkingDirectory(directory.path)

    override fun updateSearchQuery(query: String) = Unit

    override fun clearSearch() = Unit

    /** Desktop has no goal coordinator; the port reports `goals = false`. */
    override fun refreshGoalStatus() = Unit

    override fun continueGoal() = Unit
}
