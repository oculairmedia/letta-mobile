package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.feature.chat.send.LocalRuntimeChatSendStrategy
import com.letta.mobile.feature.chat.send.TimelineChatSendStrategy
import com.letta.mobile.feature.chat.send.WsChatSendStrategy
import com.letta.mobile.runtime.RuntimeEventOutbox
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Wires send strategies and the composer coordinator for admin chat.
 */
internal class AdminChatSendPipeline(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val isFreshRoute: Boolean,
    private val explicitConversationId: String?,
    private val projectContextAvailable: Boolean,
    private val conversationRepository: IConversationRepository,
    private val timelineRepository: TimelineRepository,
    private val settingsRepository: ISettingsRepository,
    private val sessionManager: SessionManager,
    private val messageRepository: MessageRepository,
    private val slashCommandRepository: com.letta.mobile.data.repository.api.ISlashCommandRepository,
    private val wsChatBridge: WsChatBridge,
    private val runtimeEventOutbox: RuntimeEventOutbox,
    private val clientVersionProvider: ChatClientVersionProvider,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val composerController: ChatComposerController,
    private val chatBannerController: ChatBannerController,
    private val isShimBackend: () -> Boolean,
    private val activeConversationId: () -> String?,
    private val setActiveConversationId: (String?) -> Unit,
    private val startTimelineObserver: (conversationId: String) -> Unit,
) {
    val timelineSendCoordinator: TimelineSendCoordinator by lazy {
        TimelineSendCoordinator(
            scope = scope,
            agentId = agentId.value,
            isFreshRoute = isFreshRoute,
            explicitConversationId = explicitConversationId,
            conversationRepository = conversationRepository,
            timelineRepository = timelineRepository,
            uiState = uiState,
            clearComposerAfterSend = { composerController.clearAfterSend() },
            activeConversationId = activeConversationId,
            setActiveConversationId = setActiveConversationId,
            startTimelineObserver = startTimelineObserver,
        )
    }

    val timelineChatSendStrategy: TimelineChatSendStrategy by lazy {
        TimelineChatSendStrategy(timelineSendCoordinator)
    }

    val wsChatSendCoordinator: WsChatSendCoordinator by lazy {
        WsChatSendCoordinator(
            scope = scope,
            agentId = agentId.value,
            activeConfig = { settingsRepository.activeConfig.value },
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = conversationRepository,
            uiState = uiState,
            clearComposerAfterSend = { composerController.clearAfterSend() },
            activeConversationId = activeConversationId,
            isFreshRoute = isFreshRoute,
            setActiveConversationId = setActiveConversationId,
            startTimelineObserver = startTimelineObserver,
            clientVersionProvider = clientVersionProvider,
            backendDescriptor = { sessionManager.current.backendDescriptor },
            runtimeEventSink = { drafts ->
                drafts.forEach { draft -> runtimeEventOutbox.append(draft) }
            },
        )
    }

    val wsChatSendStrategy: WsChatSendStrategy by lazy {
        WsChatSendStrategy(wsChatSendCoordinator)
    }

    val localRuntimeChatSendCoordinator: LocalRuntimeChatSendCoordinator by lazy {
        LocalRuntimeChatSendCoordinator(
            scope = scope,
            agentId = agentId.value,
            localBackend = { sessionManager.current.localRuntimeBackend },
            timelineRepository = timelineRepository,
            uiState = uiState,
            clearComposerAfterSend = { composerController.clearAfterSend() },
            activeConversationId = activeConversationId,
            setActiveConversationId = setActiveConversationId,
            startTimelineObserver = startTimelineObserver,
        )
    }

    val localRuntimeChatSendStrategy: LocalRuntimeChatSendStrategy by lazy {
        LocalRuntimeChatSendStrategy(localRuntimeChatSendCoordinator)
    }

    val chatSendStrategySelector: ChatSendStrategySelector by lazy {
        ChatSendStrategySelector(
            timelineStrategy = timelineChatSendStrategy,
            wsStrategy = wsChatSendStrategy,
            localStrategy = localRuntimeChatSendStrategy,
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
            isShimBackend = isShimBackend,
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            slashCommandRepository = slashCommandRepository,
            isStreaming = { uiState.value.isStreaming },
            projectContextAvailable = projectContextAvailable,
        )
    }

    fun ensureEagerInit() {
        composerCoordinator
    }
}
