package com.letta.mobile.feature.chat

import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.repository.MessageRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Handles older-message backfill without letting live timeline emissions drop the prefix. */
internal class ChatHistoryPager(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val messageRepository: MessageRepository,
    private val chatTimelineObserver: ChatTimelineObserver,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val activeConversationId: () -> String?,
) {
    fun loadOlderMessages(clientModeEnabled: Boolean) {
        if (clientModeEnabled) return
        val conversationId = activeConversationId() ?: return
        val currentState = uiState.value
        if (
            currentState.isLoadingMessages ||
            currentState.isLoadingOlderMessages ||
            !currentState.hasMoreOlderMessages ||
            currentState.isStreaming
        ) {
            return
        }

        val oldestLoadedMessageId = currentState.messages
            .firstOrNull { !it.isPending }
            ?.id
            ?: return

        scope.launch {
            uiState.value = uiState.value.copy(isLoadingOlderMessages = true)
            try {
                val olderMessages = messageRepository.fetchOlderMessages(
                    agentId = agentId,
                    conversationId = conversationId,
                    beforeMessageId = oldestLoadedMessageId,
                )
                if (conversationId != activeConversationId()) {
                    return@launch
                }

                val olderUi = olderMessages.toUiMessages()
                val mergedMessages = chatTimelineObserver.mergeOlderPage(
                    conversationId = conversationId,
                    olderMessages = olderUi,
                    existingMessages = uiState.value.messages,
                )
                uiState.value = uiState.value.copy(
                    messages = mergedMessages.toImmutableList(),
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = olderMessages.size >= MessageRepository.OLDER_MESSAGES_PAGE_SIZE,
                )
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to load older messages", e)
                if (conversationId == activeConversationId()) {
                    uiState.value = uiState.value.copy(isLoadingOlderMessages = false)
                }
            }
        }
    }
}
