package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.context.ContextWindowUsagePolicy
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatBranchView
import com.letta.mobile.ui.chat.session.ChatComposerHostInputs
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatExtensionActionState
import com.letta.mobile.ui.chat.session.ChatModelHandle
import com.letta.mobile.ui.chat.session.ChatModelSource
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionExtensions
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.DerivedStateFlow
import com.letta.mobile.ui.chat.session.TimelineChatSessionOptions
import com.letta.mobile.ui.chat.session.TimelineLocalState
import com.letta.mobile.ui.chat.session.withChrome
import com.letta.mobile.ui.chat.session.withExtensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-bglj6.1: Android's [ChatSessionPort], binding the shared chat page to the
 * existing [AdminChatViewModel] without moving any of its state.
 *
 * letta-mobile-by4vm.98.4: the chips, the send queue, the composer's commands, mentionables and
 * context usage, A2UI, branching and the live status are the shared extension points
 * ([ChatSessionExtensions]) bound to the ViewModel, as desktop's port binds them to its controller.
 *
 * @param scope the ViewModel's scope; the derived composer flow lives exactly as long as it.
 * @param onOpenBugReport see [AdminChatActions].
 */
internal class AdminChatSessionPort(
    private val viewModel: AdminChatViewModel,
    scope: CoroutineScope,
    onOpenBugReport: () -> Unit = {},
    /** letta-mobile-bzvro.15/.16: navigates to a fork; null leaves fork and edit off. */
    onOpenConversation: ((agentId: String, conversationId: String) -> Unit)? = null,
    /** letta-mobile-bzvro.37: workspace files for the composer's `@` suggestions. */
    fileMentions: Flow<List<Mentionable>> = flowOf(emptyList()),
) : ChatSessionPort {
    /** letta-mobile-7mys7: the page can open a fork and the backend can make one. */
    private val branching = onOpenConversation?.let(viewModel::branching)

    private val options = TimelineChatSessionOptions(
        permissionModes = viewModel.permissionModes,
        models = AdminChatModels(viewModel, scope),
        sendQueue = viewModel.sendQueue,
        composerHost = hostInputs(scope, fileMentions),
        onA2uiAction = viewModel::submitA2uiAction,
        branching = branching,
        attachmentLimits = viewModel.attachmentLimits,
    )
    private val extensions = ChatSessionExtensions(options, scope)

    override val uiState: StateFlow<ChatUiState> = viewModel.uiState

    /**
     * Send readiness is read from the ViewModel at each emission; its session reducer mirrors
     * every change into [ChatUiState], so sampling on [uiState] stays current.
     */
    override val composer: StateFlow<ChatComposerUiState> =
        combine(viewModel.composerState, viewModel.uiState, extensions.chrome) { typed, _, chrome -> draft(typed).withChrome(chrome) }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialComposer())

    override val actions: ChatActions = AdminChatActions(
        viewModel = viewModel,
        options = options,
        // The ViewModel keeps its own (saved-state) run expansion and error, so the shared local
        // state and error only answer the branch view here.
        state = ChatExtensionActionState(MutableStateFlow(TimelineLocalState()), branchView = ::branchView, error = { null }),
        onOpenBugReport = onOpenBugReport,
        branching = branching,
    )

    /** letta-mobile-bzvro.7: the run state the view model folds from this screen's runtime events. */
    override val liveStatus: StateFlow<RuntimeLiveStatus> = viewModel.liveStatus

    /** Android's own support, with what its extension points turn on (the model switch, branching). */
    override val capabilities: StateFlow<ChatSurfaceCapabilities> =
        DerivedStateFlow(extensions.facts.map(Capabilities::withExtensions)) { Capabilities.withExtensions(extensions.facts.value) }

    private fun initialComposer() = draft(viewModel.composerState.value).withChrome(extensions.chrome.value)

    private fun draft(composer: ChatComposerState) =
        AdminChatComposerMapping.composerUiState(composer, viewModel.canSendMessages, viewModel.attachmentLimits)

    private fun branchView() = viewModel.uiState.value.let { ChatBranchView(it.messages, it.hasMoreOlderMessages) }

    /**
     * The composer's commands, `@` files and context chip. letta-mobile-0ofhc: the chip follows the
     * streamed reading on its own — when the chat opens, after every settled turn, and on a
     * conversation switch — with no drawer tap involved.
     */
    private fun hostInputs(scope: CoroutineScope, fileMentions: Flow<List<Mentionable>>): StateFlow<ChatComposerHostInputs> {
        val usage = AdminChatContextSources(
            agentId = viewModel.agentId.value,
            ui = viewModel.uiState,
            readings = viewModel.contextReadings,
            agent = viewModel.activeAgent,
            models = viewModel.llmModels,
            modelSelections = viewModel.conversationModelSelections,
        ).contextUsage()
        val slash = viewModel.composerState.map { it.slashCommands }.distinctUntilChanged()
        return combine(slash, usage, fileMentions) { commands, contextUsage, files ->
            ChatComposerHostInputs(hostCommands(commands), files, contextUsage)
        }.stateIn(
            scope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            ChatComposerHostInputs(hostCommands(viewModel.composerState.value.slashCommands), contextUsage = INITIAL_USAGE),
        )
    }

    private fun hostCommands(slash: List<SlashCommand>) = AdminChatComposerMapping.hostCommands(
        slash,
        canFork = branching != null,
        select = viewModel::selectSlashCommand,
        uninstall = viewModel::uninstallSlashCommand,
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** The context chip's placeholder until the first reading. */
        private val INITIAL_USAGE = ContextWindowUsagePolicy.cleared()

        val Capabilities = ChatSurfaceCapabilities(
            attachImages = true,
            rerun = true,
            approvals = true,
            search = true,
            pagedHistory = true,
            fontScale = true,
            goals = true,
        )
    }
}

/** The conversation's model chip and switch over the ViewModel's agent, models and per-conversation picks. */
internal class AdminChatModels(private val viewModel: AdminChatViewModel, scope: CoroutineScope) : ChatModelSource {
    override val model: StateFlow<ChatModelUiState?> =
        combine(viewModel.activeAgent, viewModel.llmModels, viewModel.conversationModelSelections, ::chip).stateIn(
            scope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            chip(viewModel.activeAgent.value, viewModel.llmModels.value, viewModel.conversationModelSelections.value),
        )

    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) =
        viewModel.updateActiveAgentModel(handle.value, AdminChatComposerMapping.effortSelection(effort))

    private fun chip(agent: Agent?, models: List<LlmModel>, selections: ConversationModelChoices): ChatModelUiState? {
        val conversation = viewModel.conversationId?.value
        return AdminChatComposerMapping.modelUiState(
            AdminChatComposerMapping.ModelInputs(
                agent = agent,
                models = models,
                conversationOverride = conversation?.let(selections.models::get),
                effortsFor = viewModel::reasoningEffortsFor,
                conversationEffort = conversation?.let(selections.efforts::get),
            ),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
