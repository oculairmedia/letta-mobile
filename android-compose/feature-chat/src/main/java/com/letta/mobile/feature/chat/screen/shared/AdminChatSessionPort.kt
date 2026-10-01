package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-bglj6.1: Android's [ChatSessionPort], binding the shared chat page to the
 * existing [AdminChatViewModel] without moving any of its state.
 *
 * @param scope the ViewModel's scope; the derived composer flow lives exactly as long as it.
 * @param onOpenBugReport see [AdminChatActions].
 */
internal class AdminChatSessionPort(
    private val viewModel: AdminChatViewModel,
    scope: CoroutineScope,
    onOpenBugReport: () -> Unit = {},
) : ChatSessionPort {

    override val uiState: StateFlow<ChatUiState> = viewModel.uiState

    override val composer: StateFlow<ChatComposerUiState> =
        combine(viewModel.composerState, viewModel.uiState, modelFlow(), ::composerSnapshot)
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialComposer())

    override val actions: ChatActions = AdminChatActions(viewModel, onOpenBugReport)

    /** Android's support does not change while the page is open. */
    override val capabilities: StateFlow<ChatSurfaceCapabilities> = MutableStateFlow(Capabilities)

    private fun initialComposer(): ChatComposerUiState = composerSnapshot(
        viewModel.composerState.value,
        viewModel.uiState.value,
        modelState(viewModel.activeAgent.value, viewModel.llmModels.value, viewModel.conversationModelSelections.value),
    )

    private fun modelFlow(): Flow<ChatModelUiState?> =
        combine(viewModel.activeAgent, viewModel.llmModels, viewModel.conversationModelSelections, ::modelState)

    private fun modelState(
        agent: Agent?,
        models: List<LlmModel>,
        selections: Map<String, String>,
    ): ChatModelUiState? = AdminChatComposerMapping.modelUiState(
        AdminChatComposerMapping.ModelInputs(
            agent = agent,
            models = models,
            conversationOverride = viewModel.conversationId?.value?.let(selections::get),
            effortsFor = viewModel::reasoningEffortsFor,
        ),
    )

    /**
     * Send readiness is read from the ViewModel at each emission; its session reducer mirrors
     * every change into [ChatUiState], so sampling on [uiState] stays current.
     */
    private fun composerSnapshot(
        composer: ChatComposerState,
        ui: ChatUiState,
        model: ChatModelUiState?,
    ): ChatComposerUiState =
        AdminChatComposerMapping.composerUiState(
            AdminChatComposerMapping.ComposerInputs(
                composer = composer,
                canSend = viewModel.canSendMessages,
                canQueueWhileStreaming = viewModel.canQueueWhileStreaming,
                maxAttachments = viewModel.attachmentLimits.maxAttachmentCount,
                model = model,
                contextWindow = ui.contextWindow,
            ),
        )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Capabilities = ChatSurfaceCapabilities(
            attachImages = true,
            rerun = true,
            approvals = true,
            modelSwitch = true,
            workingDirectory = false,
            search = true,
            pagedHistory = true,
            fontScale = true,
            goals = true,
        )
    }
}
