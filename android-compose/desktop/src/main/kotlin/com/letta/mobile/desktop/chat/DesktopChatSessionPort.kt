package com.letta.mobile.desktop.chat

import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.presence.ActiveTurnPhase
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatBranchView
import com.letta.mobile.ui.chat.session.ChatComposerHostInputs
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatExtensionActionState
import com.letta.mobile.ui.chat.session.ChatExtensionActions
import com.letta.mobile.ui.chat.session.ChatSessionExtensions
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.GatewayPermissionModes
import com.letta.mobile.ui.chat.session.TimelineChatSessionOptions
import com.letta.mobile.ui.chat.session.TimelineLocalState
import com.letta.mobile.ui.chat.session.TimelineHostApprovals
import com.letta.mobile.ui.chat.session.currentHostApprovals
import com.letta.mobile.ui.chat.session.forgetAcknowledgedOnceCleared
import com.letta.mobile.ui.chat.session.hostApprovals
import com.letta.mobile.ui.chat.session.runLiveStatus
import com.letta.mobile.ui.chat.session.withChrome
import com.letta.mobile.ui.chat.session.withExtensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What the shell hands the port beyond the controller itself. */
internal data class DesktopChatSessionBindings(
    /** The shell's A2UI dispatch (resolves the conversation id, then sends over the channel transport). */
    val onA2uiAction: (A2uiAction) -> Unit = {},
    /** Persists a pinch/zoom font scale through the desktop font-scale host. */
    val onSetFontScale: (Float) -> Unit = {},
    /** Model catalog and available reasoning efforts; null hides the effort chip. */
    val modelCatalog: ModelCatalogRepository? = null,
)

/** Map controller effects and workspace focus to the shared chat session. */
internal class DesktopChatSessionPort(
    internal val controller: DesktopChatController,
    private val scope: CoroutineScope,
    private val bindings: DesktopChatSessionBindings = DesktopChatSessionBindings(),
) : ChatSessionPort {
    private val hostInputs = MutableStateFlow(ChatComposerHostInputs())
    private val localTimeline = MutableStateFlow(TimelineLocalState())
    private val surface = controller.workspaceOwner?.let { owner ->
        DerivedStateFlow(combine(controller.state, snapshotFlow {
            Triple(owner.currentFocus, owner.agentAvailability, owner.conversationAvailability)
        }) { state, _ -> state.projectWorkspace(owner, controller.isTargetCurrent()) },
            { controller.state.value.projectWorkspace(owner, controller.isTargetCurrent()) })
    } ?: controller.state

    /** Shared conversation extensions, including the window-retained active-turn owner. */
    private val options = TimelineChatSessionOptions(
        workingDirectory = controller.workingDirectory,
        permissionModes = GatewayPermissionModes(
            scope = scope,
            targets = surface.map { controller.activeGateway.permissionModeTarget(it.selectedConversation) },
            current = { controller.activeGateway.permissionModeTarget(surface.value.selectedConversation) },
        ),
        models = controller.models,
        sendQueue = controller.sendQueue,
        composerHost = hostInputs,
        onA2uiAction = bindings.onA2uiAction,
        branching = controller.conversationManagement.branches,
        recompile = controller.conversationManagement.recompile,
        liveStatus = runLiveStatus(controller.runs, surface.map { it.selectedConversationId }),
        attachmentLimits = controller.attachmentLimits,
        activeTurnCoordinator = controller.activeTurnCoordinator(surface.value.selectedConversationId),
    )
    private val extensions = ChatSessionExtensions(options, scope)

    init {
        controller.models.bindCatalog(bindings.modelCatalog, follow = scope)
    }

    /** Feeds the composer chrome the shell computes (commands, mentionables, context usage). */
    fun updateHostInputs(inputs: ChatComposerHostInputs) {
        hostInputs.value = inputs
    }

    override val uiState: StateFlow<ChatUiState> = DerivedStateFlow(
        timelineInputs().runningFold(null as ChatUiState?) { previous, inputs -> desktopChatUiState(inputs, previous) }
            .map { it ?: initialUiState() }, ::initialUiState,
    )

    override val composer: StateFlow<ChatComposerUiState> = DerivedStateFlow(
        combine(surface, extensions.chrome) { surface, chrome ->
            surface.composerUiState(controller.attachmentLimits).let { if (controller.isSelectionCurrent()) it.withChrome(chrome) else it }
        },
        { surface.value.composerUiState(controller.attachmentLimits).let {
            if (controller.isSelectionCurrent()) it.withChrome(extensions.chrome.value) else it
        } },
    )

    override val liveStatus: StateFlow<RuntimeLiveStatus> get() = extensions.liveStatus

    /** letta-mobile-zxk2n.5: what the Chat options show and hide, for the page's appearance. */
    val displayOptions: StateFlow<ChatDisplayOptions> = controller.conversationMarks.displayOptions

    override val recompileStatus: StateFlow<RecompileStatus> get() = extensions.recompileStatus

    override val actions: ChatActions = DesktopChatActions(
        controller = controller,
        options = options,
        state = ChatExtensionActionState(
            local = localTimeline,
            branchView = { uiState.value.let { ChatBranchView(it.messages, it.hasMoreOlderMessages) } },
            error = { controller.state.value.errorMessage },
        ),
        onSetFontScale = bindings.onSetFontScale,
    )

    /**
     * Follows the controller: approval support follows the active gateway and paged history the
     * canonical (paged) route, both of which change after construction; the extension points
     * (working directory, model switch, branching) follow their own sources.
     */
    override val capabilities: StateFlow<ChatSurfaceCapabilities> = combine(
        controller.approvals.canSubmitApprovals,
        controller.canonicalPresentation,
        extensions.facts,
    ) { approvals, canonical, facts -> desktopCapabilities(approvals, paged = canonical != null).withExtensions(facts) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), currentCapabilities())

    private fun currentCapabilities(): ChatSurfaceCapabilities = desktopCapabilities(
        approvals = controller.approvals.canSubmitApprovals.value,
        paged = controller.canonicalPresentation.value != null,
    ).withExtensions(extensions.facts.value)

    private fun initialUiState(): ChatUiState = desktopChatUiState(currentTimelineInputs(), previous = null)

    private fun currentTimelineInputs() = DesktopChatTimelineInputs(
        surface = surface.value,
        presence = if (controller.isSelectionCurrent()) controller.replyPresence.value else ChatStreamingPresence(false, false),
        cancellingConversationId = surface.value.selectedConversationId.takeIf {
            controller.isSelectionCurrent() && controller.activeTurnStates.value[it]?.phase == ActiveTurnPhase.Cancelling
        },
        sendQueue = ConversationSendQueue(),
        local = localTimeline.value,
        approvals = if (controller.isSelectionCurrent()) controller.approvals.currentHostApprovals() else TimelineHostApprovals(),
    )

    private fun timelineInputs(): Flow<DesktopChatTimelineInputs> = combine(
        surface,
        controller.replyPresence,
        controller.activeTurnStates,
        controller.sendQueue.queue,
        combine(localTimeline, controller.approvals.hostApprovals(), ::Pair),
    ) { surface, presence, turns, queue, (local, approvals) ->
        val cancelling = surface.selectedConversationId.takeIf { turns[it]?.phase == ActiveTurnPhase.Cancelling }
        if (controller.workspaceOwner != null && !controller.isSelectionCurrent()) {
            DesktopChatTimelineInputs(surface, ChatStreamingPresence(false, false), null, ConversationSendQueue(), local,
                TimelineHostApprovals())
        } else DesktopChatTimelineInputs(surface, presence, cancelling, queue, local, approvals)
    }.onEach { inputs ->
        // Once the error is gone (a send cleared it), the same message later is a new error.
        localTimeline.update { it.forgetAcknowledgedOnceCleared(inputs.surface.errorMessage) }
    }
}

/**
 * What desktop's controller supports beyond the extension points: approvals where the active
 * gateway answers them ([approvals]) and paged history on the canonical route ([paged]).
 */
internal fun desktopCapabilities(approvals: Boolean, paged: Boolean) = ChatSurfaceCapabilities(
    attachImages = true,
    rerun = false,
    approvals = approvals,
    modelSwitch = true,
    search = false,
    pagedHistory = paged,
    fontScale = true,
    goals = false,
)

/**
 * The port's flows run only while the page collects them, so a port left behind by a controller
 * change stops on its own instead of needing a scope of its own to cancel.
 */
private const val STOP_TIMEOUT_MS = 5_000L

/** [ChatActions] forwarded to [DesktopChatController]; the extension points answer the rest. */
internal class DesktopChatActions(
    private val controller: DesktopChatController,
    options: TimelineChatSessionOptions,
    state: ChatExtensionActionState,
    private val onSetFontScale: (Float) -> Unit = {},
) : ChatExtensionActions(options, state) {
    override fun updateComposerText(text: String) { if (controller.canEditDraft()) controller.updateComposerText(text) }

    override fun send() = controller.sendOrBranch()

    /** A draft-free send: the user's draft and staged images stay exactly as they are. */
    override fun sendText(text: String) = controller.sendText(text)

    override fun attachImage(image: MessageContentPart.Image) { if (controller.canEditDraft()) controller.attachImage(image) }

    override fun removeAttachment(index: Int) { if (controller.canEditDraft()) controller.removeImageAttachment(index) }

    override fun reportComposerError(message: String) = controller.showComposerError(message)

    override fun clearComposerError() = controller.clearComposerError()

    override fun stopRun() {
        if (controller.isSelectionCurrent()) controller.state.value.selectedConversationId?.let(controller::stopActiveRun)
    }

    override fun submitApproval(answer: ChatApprovalAnswer) {
        // A second press while the first answer is in flight must not answer twice.
        if (answer.requestId in controller.approvals.submittingApprovals.value) return
        if (controller.approvals.canSubmitApprovals.value) {
            controller.submitApproval(answer)
        }
    }

    override fun retryLoad() = controller.retryConnection()

    override fun setFontScale(scale: Float) = onSetFontScale(scale)
}
