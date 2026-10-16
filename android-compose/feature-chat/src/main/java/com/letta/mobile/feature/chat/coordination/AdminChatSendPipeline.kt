package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.feature.chat.send.ChatSendStrategy
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.runtime.RuntimeEventOutbox
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.runtime.isPresentationOnly
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** The repositories and channels a send reaches. */
internal data class AdminChatSendSources(
    val conversationRepository: IConversationRepository,
    val settingsRepository: ISettingsRepository,
    val sessionManager: SessionManager,
    val slashCommandRepository: com.letta.mobile.data.repository.api.ISlashCommandRepository,
    val wsChatBridge: WsChatBridge,
    val runtimeEventOutbox: RuntimeEventOutbox,
    val clientVersionProvider: ChatClientVersionProvider,
)

/** The screen state a send updates. */
internal data class AdminChatSendUi(
    val uiState: MutableStateFlow<ChatUiState>,
    val composerController: ChatComposerController,
    val chatBannerController: ChatBannerController,
)

/** What the pipeline asks of its screen. */
internal data class AdminChatSendHooks(
    val submitApproval: (String, List<String>, Boolean, String?) -> Unit,
    val backendKind: () -> BackendKind,
    val activeConversationId: () -> String?,
    val setActiveConversationId: (String?) -> Unit,
    val startTimelineObserver: (conversationId: String) -> Unit,
    /**
     * Every runtime event this screen's transport produces, as it is recorded. The view model
     * folds these through the shared run-phase reducer so the registry carries honest phases;
     * the pipeline itself stays a wire and owns no presence logic.
     */
    val runtimeEventObserver: (List<com.letta.mobile.runtime.RuntimeEventDraft>) -> Unit = {},
)

/**
 * Wires send strategies and the composer coordinator for admin chat.
 */
internal class AdminChatSendPipeline(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val explicitConversationId: String?,
    private val projectContextAvailable: Boolean,
    sources: AdminChatSendSources,
    ui: AdminChatSendUi,
    hooks: AdminChatSendHooks,
    private val selectedOwner: SelectedChatSendOwner? = null,
) {
    private val conversationRepository = sources.conversationRepository
    private val settingsRepository = sources.settingsRepository
    private val sessionManager = sources.sessionManager
    private val slashCommandRepository = sources.slashCommandRepository
    private val wsChatBridge = sources.wsChatBridge
    private val runtimeEventOutbox = sources.runtimeEventOutbox
    private val clientVersionProvider = sources.clientVersionProvider
    private val uiState = ui.uiState
    private val composerController = ui.composerController
    private val chatBannerController = ui.chatBannerController
    private val submitApproval = hooks.submitApproval
    private val backendKind = hooks.backendKind
    private val activeConversationId = hooks.activeConversationId
    private val setActiveConversationId = hooks.setActiveConversationId
    private val startTimelineObserver = hooks.startTimelineObserver
    private val runtimeEventObserver = hooks.runtimeEventObserver

    suspend fun retireSelectedOwner() { selectedOwner?.retire() }
    /** The captured runtime's canonical writer; without one a send fails instead of writing nowhere. */
    private val writer: com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter =
        selectedOwner?.writer ?: UnboundTimelineWriter

    val wsChatSendCoordinator: WsChatSendCoordinator by lazy {
        WsChatSendCoordinator(
            scope = selectedOwner?.scope ?: scope,
            agentId = agentId.value,
            activeConfig = {
                selectedOwner?.let { it.requireCurrent(); it.config } ?: settingsRepository.activeConfig.value
            },
            wsChatBridge = wsChatBridge,
            timelineRepository = writer,
            conversationRepository = conversationRepository,
            uiState = uiState,
            clearComposerAfterSend = { composerController.clearAfterSend() },
            activeConversationId = activeConversationId,
            setActiveConversationId = setActiveConversationId,
            startTimelineObserver = startTimelineObserver,
            clientVersionProvider = clientVersionProvider,
            prepareConversation = { conversation -> selectedOwner?.ready(conversation) },
            backendDescriptor = {
                selectedOwner?.let { it.requireCurrent(); it.descriptor } ?: sessionManager.current.backendDescriptor
            },
            runtimeEventSink = { drafts ->
                runtimeEventObserver(drafts)
                // letta-mobile-bzvro.7: status-line events feed the run state, not the durable outbox.
                drafts.filterNot { it.payload.isPresentationOnly }
                    .takeIf { it.isNotEmpty() }
                    ?.let { runtimeEventOutbox.appendAll(it) }
            },
        )
    }

    /**
     * lgns8.10.4.1 / by4vm.110.6: the one route drives [wsChatSendCoordinator] over whichever
     * `IChannelTransport` the session graph bound (Iroh, ws:// or the on-device runtime).
     */
    val chatSendStrategySelector: ChatSendStrategySelector by lazy {
        ChatSendStrategySelector(
            ChatSendStrategy("channel", wsChatSendCoordinator::send) { wsChatSendCoordinator.cancel() },
        )
    }

    val composerCoordinator: AdminChatComposerCoordinator by lazy {
        AdminChatComposerCoordinator(
            scope = scope,
            composerController = composerController,
            chatSendStrategySelector = chatSendStrategySelector,
            chatBannerController = chatBannerController,
            uiState = uiState,
            agentId = agentId,
            explicitConversationId = explicitConversationId,
            backendKind = backendKind,
            slashCommandRepository = slashCommandRepository,
            isStreaming = { uiState.value.isStreaming },
            pendingUserInput = {
                uiState.value.messages.asReversed().firstNotNullOfOrNull { message ->
                    val approval = message.approvalRequest ?: return@firstNotNullOfOrNull null
                    val toolCall = approval.toolCalls.singleOrNull {
                        it.name == AskUserQuestion.ASK_USER_QUESTION_TOOL
                    } ?: return@firstNotNullOfOrNull null
                    val question = AskUserQuestion.parse(toolCall.arguments)
                        ?.questions
                        ?.singleOrNull()
                        ?.question
                        ?: return@firstNotNullOfOrNull null
                    AdminChatComposerCoordinator.PendingUserInput(
                        requestId = approval.requestId,
                        toolCallId = toolCall.toolCallId,
                        arguments = toolCall.arguments,
                        question = question,
                    )
                }
            },
            submitUserInput = { pending, reason ->
                submitApproval(pending.requestId, listOf(pending.toolCallId), true, reason)
            },
            projectContextAvailable = projectContextAvailable,
        )
    }

    /** A route's initial message goes straight to the backend's send strategy, past the composer's gates. */
    fun sendInitialMessage(text: String) {
        chatSendStrategySelector.send(text, emptyList(), composerCoordinator.chatSendContext())
    }

    fun ensureEagerInit() {
        composerCoordinator
    }
}
