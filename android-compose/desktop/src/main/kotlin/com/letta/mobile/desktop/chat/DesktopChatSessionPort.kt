package com.letta.mobile.desktop.chat

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.CatalogModel
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.recompile.RecompileStatus
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
import com.letta.mobile.ui.chat.session.ChatRunId
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatWorkingDirectory
import com.letta.mobile.ui.chat.session.GatewayPermissionModes
import com.letta.mobile.ui.chat.session.currentHostApprovals
import com.letta.mobile.ui.chat.session.hostApprovals
import kotlinx.coroutines.CancellationException
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
    /**
     * letta-mobile-by4vm.85: the host's model catalog (the window's model-control session), whose
     * rows say which reasoning efforts each model takes; null leaves the effort chip off.
     */
    val modelCatalog: ModelCatalogRepository? = null,
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

    /** letta-mobile-bzvro.13, by4vm.98: the selected conversation's mode chip, the shared gateway binding. */
    private val permissionModes = GatewayPermissionModes(
        scope = scope,
        targets = controller.state.map { controller.activeGateway.permissionModeTarget(it.selectedConversation) },
        current = { controller.activeGateway.permissionModeTarget(controller.state.value.selectedConversation) },
    )

    init {
        followModelCatalog()
    }

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

    /** letta-mobile-zxk2n.5: what the Chat options show and hide, for the page's appearance. */
    val displayOptions: StateFlow<ChatDisplayOptions> = controller.conversationManagement.marks.displayOptions

    /** letta-mobile-zxk2n.5: `/recompile`'s status, from the controller. */
    override val recompileStatus: StateFlow<RecompileStatus> = controller.conversationManagement.recompile.status

    override val actions: ChatActions = DesktopChatActions(
        controller = controller,
        bindings = bindings,
        commandsById = { hostInputs.value.commands.associateBy(ComposerCommand::label) },
        localTimeline = localTimeline,
        branchView = { uiState.value.let { DesktopBranchView(it.messages, it.hasMoreOlderMessages) } },
        onSetPermissionMode = permissionModes::setPermissionMode,
    )

    /**
     * Follows the controller: approval support and the working directory follow the active
     * gateway, and paged history the canonical (paged) route, all of which change after
     * construction. The gateway has no flow of its own, so a state change re-reads it.
     */
    override val capabilities: StateFlow<ChatSurfaceCapabilities> = combine(
        controller.approvals.canSubmitApprovals,
        controller.canonicalPresentation,
        controller.workingDirectory.current.map { it != null }.distinctUntilChanged(),
        controller.state.map { branchFacts() }.distinctUntilChanged(),
    ) { approvals, canonical, workingDirectory, branching ->
        desktopCapabilities(
            DesktopCapabilityFacts(
                approvals,
                paged = canonical != null,
                workingDirectory = workingDirectory,
                branching = branching.first,
                namedForks = branching.second,
            ),
        )
    }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), currentCapabilities())

    private fun currentCapabilities(): ChatSurfaceCapabilities = desktopCapabilities(
        DesktopCapabilityFacts(
            approvals = controller.approvals.canSubmitApprovals.value,
            paged = controller.canonicalPresentation.value != null,
            workingDirectory = controller.workingDirectory.current.value != null,
            branching = controller.conversationManagement.supportsBranching,
            namedForks = controller.conversationManagement.canNameForks,
        ),
    )

    /** letta-mobile-7mys7: whether the gateway can fork, and whether it can name a fork. */
    private fun branchFacts(): Pair<Boolean, Boolean> =
        controller.conversationManagement.let { it.supportsBranching to it.canNameForks }

    private fun initialUiState(): ChatUiState = desktopChatUiState(currentTimelineInputs(), previous = null)

    private fun currentTimelineInputs() = DesktopChatTimelineInputs(
        surface = controller.state.value,
        presence = controller.replyPresence.value,
        cancellingConversationId = controller.cancellingConversationId.value,
        sendQueue = ConversationSendQueue(),
        local = localTimeline.value,
        approvals = controller.approvals.currentHostApprovals(),
    )

    private fun timelineInputs(): Flow<DesktopChatTimelineInputs> = combine(
        controller.state,
        controller.replyPresence,
        controller.cancellingConversationId,
        selectedSendQueue(),
        combine(localTimeline, controller.approvals.hostApprovals(), ::Pair),
    ) { surface, presence, cancelling, queue, (local, approvals) ->
        DesktopChatTimelineInputs(surface, presence, cancelling, queue, local, approvals)
    }.onEach { inputs ->
        // Once the error is gone (a send cleared it), the same message later is a new error.
        if (inputs.surface.errorMessage == null && inputs.local.acknowledgedError != null) {
            localTimeline.update { it.copy(acknowledgedError = null) }
        }
    }

    /**
     * The selected conversation's queue, re-resolved whenever the selection or the canonical route
     * changes.
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
        modelEfforts = desktopModelEfforts(currentModelEffortInputs()),
        workingDirectory = controller.workingDirectory.current.value,
        canQueueWhileStreaming = controller.canonicalPresentation.value != null,
        attachmentLimits = controller.attachmentLimits,
    )

    private fun composerInputs(): Flow<DesktopChatComposerInputs> = combine(
        controller.state,
        hostInputs,
        combine(controller.availableModels, modelEfforts(), ::Pair),
        combine(controller.workingDirectory.current, permissionModes.permissionMode, ::Pair),
        controller.canonicalPresentation,
    ) { surface, host, (models, efforts), (workingDirectory, permissionMode), canonical ->
        DesktopChatComposerInputs(
            surface = surface,
            host = host,
            modelOptions = buildModelOptions(models),
            modelEfforts = efforts,
            workingDirectory = workingDirectory,
            canQueueWhileStreaming = canonical != null,
            attachmentLimits = controller.attachmentLimits,
            permissionMode = permissionMode,
        )
    }

    /** letta-mobile-by4vm.85: the efforts the composer offers, following the catalog and the picks. */
    private fun modelEfforts(): Flow<DesktopModelEfforts> = combine(
        controller.availableModels,
        catalogRows(),
        controller.conversationModelSelections,
        controller.state.map { it.selectedConversationId }.distinctUntilChanged(),
    ) { models, catalog, choices, selectedId ->
        desktopModelEfforts(
            DesktopModelEffortInputs(models, catalog, choices, selectedId, controller.supportsReasoningEffort),
        )
    }.distinctUntilChanged()

    private fun catalogRows(): Flow<List<CatalogModel>> = bindings.modelCatalog?.models ?: flowOf(emptyList())

    private fun currentModelEffortInputs() = DesktopModelEffortInputs(
        models = controller.availableModels.value,
        catalog = bindings.modelCatalog?.models?.value.orEmpty(),
        choices = controller.conversationModelSelections.value,
        selectedConversationId = controller.state.value.selectedConversationId,
        supported = controller.supportsReasoningEffort,
    )

    /**
     * letta-mobile-by4vm.85: the catalog is otherwise read only when the picker opens, so each
     * model-list load (a connect, a host swap) re-reads it where the gateway can send an effort.
     * A host without the admin catalog keeps an empty one: no effort chip.
     */
    private fun followModelCatalog() {
        val catalog = bindings.modelCatalog ?: return
        scope.launch {
            controller.availableModels.collect { models ->
                if (models.isNotEmpty() && controller.supportsReasoningEffort) refreshCatalogQuietly(catalog)
            }
        }
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
    /** letta-mobile-bzvro.15/.16, 7mys7: the active gateway can fork (fork, edit and resend, rerun). */
    val branching: Boolean = false,
    /** letta-mobile-7mys7: the active gateway can retitle, so a fork can be named as it is made. */
    val namedForks: Boolean = false,
)

internal fun desktopCapabilities(facts: DesktopCapabilityFacts) = ChatSurfaceCapabilities(
    fork = facts.branching,
    editAndResend = facts.branching,
    rerunInFork = facts.branching,
    nameForks = facts.branching && facts.namedForks,
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

/** A catalog read that fails leaves the previous rows (or none: no effort chip); the next load tries again. */
private suspend fun refreshCatalogQuietly(catalog: ModelCatalogRepository) {
    try {
        catalog.refresh()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Not every host answers model.list (the direct App Server session has no admin catalog).
    }
}

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

    override fun send() = controller.sendOrBranch()

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

    override fun dismissRecompileStatus() = controller.conversationManagement.recompile.dismiss()

    override fun stopRun() {
        selectedConversationId?.let(controller::stopActiveRun)
    }

    override fun rerun(message: UiMessage) = Unit

    override fun forkFromMessage(message: UiMessage, name: String?) =
        controller.conversationManagement.forkFrom(message, branchView(), name)

    override fun editAndResend(message: UiMessage) = controller.conversationManagement.editAndResend(message, branchView())

    override fun rerunInFork(message: UiMessage) = controller.conversationManagement.rerun(message, branchView())

    override fun submitApproval(answer: ChatApprovalAnswer) {
        // A second press while the first answer is in flight must not answer twice.
        if (answer.requestId in controller.approvals.submittingApprovals.value) return
        if (controller.approvals.canSubmitApprovals.value) {
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

    /** The canonical paged timeline pages itself; the in-memory list has no older-history window. */
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

    /** letta-mobile-by4vm.85: the selection value and the effort go to the conversation together. */
    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) =
        controller.setConversationModel(handle.value, effort)

    override fun setPermissionMode(mode: AppServerPermissionMode) = onSetPermissionMode(mode)

    override fun changeWorkingDirectory(directory: ChatWorkingDirectory) =
        controller.workingDirectory.changeWorkingDirectory(directory)

    override fun updateSearchQuery(query: String) = Unit

    override fun clearSearch() = Unit

    /** Desktop has no goal coordinator; the port reports `goals = false`. */
    override fun refreshGoalStatus() = Unit

    override fun continueGoal() = Unit
}
