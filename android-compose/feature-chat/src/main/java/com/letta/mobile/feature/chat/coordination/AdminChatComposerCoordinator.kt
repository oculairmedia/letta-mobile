package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.feature.chat.send.ChatSendContext
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.feature.chat.send.TimelineChatSendStrategy
import com.letta.mobile.feature.chat.state.ChatBannerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState


internal class AdminChatComposerCoordinator(
    private val scope: CoroutineScope,
    private val composerController: ChatComposerController,
    private val chatSendStrategySelector: ChatSendStrategySelector,
    private val chatBannerController: ChatBannerController,
    private val activeConversationId: () -> String?,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val agentId: AgentId,
    private val explicitConversationId: String?,
    private val isShimBackend: () -> Boolean,
    private val sessionManager: SessionManager,
    private val messageRepository: MessageRepository,
    private val timelineChatSendStrategy: TimelineChatSendStrategy,
    private val isStreaming: () -> Boolean,
    private val projectContextAvailable: Boolean,
) {
    val state: StateFlow<ChatComposerState> = composerController.state

    fun addAttachment(image: MessageContentPart.Image): Boolean =
        composerController.addAttachment(image)

    fun removeAttachment(index: Int) {
        composerController.removeAttachment(index)
    }

    fun updateInputText(text: String) {
        composerController.updateText(text)
    }

    fun reportComposerError(message: String) {
        chatBannerController.showComposerError(message)
    }

    fun clearComposerError() {
        chatBannerController.clearComposerError()
    }

    fun handleComposerTextChanged(newText: String): ChatComposerEffect? {
        val composer = state.value
        return if (newText.endsWith("\n") && composer.hasSendableContent) {
            submitComposer(composer.inputText)
        } else {
            updateInputText(newText)
            null
        }
    }

    fun submitComposer(text: String = state.value.inputText): ChatComposerEffect? {
        return when (ChatSlashCommandParser.parse(text, projectContextAvailable = projectContextAvailable)) {
            ChatSlashCommand.Bug -> {
                composerController.clearText()
                ChatComposerEffect.OpenBugReport
            }
            null -> {
                if (isStreaming()) {
                    composerController.setError(
                        "Letta does not support free-form steering during an active run yet. Stop the run before sending another message."
                    )
                } else {
                    sendMessage(text)
                }
                null
            }
        }
    }

    fun sendMessage(text: String) {
        when (uiState.value.conversationState) {
            ConversationState.Loading -> {
                chatBannerController.showConversationStillLoading()
                return
            }
            is ConversationState.Error -> {
                chatBannerController.showRetryConversationLoadBeforeSend()
                return
            }
            ConversationState.NoConversation,
            is ConversationState.Ready,
            -> Unit
        }

        val payload = composerController.payloadForSend(text) ?: return
        sendMessagePayload(payload.text, payload.attachments)
    }

    fun rerunMessage(message: UiMessage) {
        val text = message.content.trim()
        if (message.role != "user" || text.isBlank()) return
        sendMessagePayload(text, emptyList())
    }

    private fun sendMessagePayload(
        text: String,
        attachments: List<MessageContentPart.Image>,
    ) {
        val context = chatSendContext()
        chatSendStrategySelector.send(text, attachments, context)
    }

    fun chatSendContext() = ChatSendContext(
        isClientModeEnabled = false,
        explicitConversationId = explicitConversationId,
        isShimBackend = isShimBackend(),
        isLocalRuntime = sessionManager.current.localRuntimeBackend != null,
    )

    fun interruptRun(clearA2uiThinkingOnResponse: () -> Unit) {
        if (!uiState.value.isStreaming) return
        clearA2uiThinkingOnResponse()
        val context = chatSendContext()
        scope.launch {
            if (context.isShimBackend || context.isLocalRuntime) {
                chatBannerController.clearStreamingAfterInterrupt()
                chatSendStrategySelector.cancel(context)
                return@launch
            }
            val runIds = activeRunIds().takeIf { it.isNotEmpty() }
            chatBannerController.clearStreamingAfterInterrupt()
            runCatching {
                messageRepository.cancelMessage(agentId = agentId, runIds = runIds)
            }.onFailure { e ->
                chatBannerController.showMappedError(e.asException(), "Failed to stop run")
            }
        }
    }

    private fun Throwable.asException(): Exception = this as? Exception ?: Exception(this)

    private fun activeRunIds(): List<String> = uiState.value.messages
        .asReversed()
        .mapNotNull { it.runId }
        .distinct()
        .take(1)
}
