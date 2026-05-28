package com.letta.mobile.feature.chat

import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.util.Telemetry
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
        // letta-mobile-doq50: every short-circuit path is now telemetered so
        // 'no loading indicator at all' diagnostics are answerable without
        // device repro. Pair with the catch-block 'failed' event so the full
        // skip/attempt/succeed/fail surface is visible.
        if (clientModeEnabled) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "clientModeEnabled",
                "agentId" to agentId,
            )
            return
        }
        val conversationId = activeConversationId()
        if (conversationId == null) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "noActiveConversation",
                "agentId" to agentId,
            )
            return
        }
        val currentState = uiState.value
        if (currentState.isLoadingMessages) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "isLoadingMessages",
                "conversationId" to conversationId,
            )
            return
        }
        if (currentState.isLoadingOlderMessages) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "alreadyLoadingOlder",
                "conversationId" to conversationId,
            )
            return
        }
        if (!currentState.hasMoreOlderMessages) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "noMoreOlder",
                "conversationId" to conversationId,
                "messageCount" to currentState.messages.size,
            )
            return
        }
        if (currentState.isStreaming) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "isStreaming",
                "conversationId" to conversationId,
            )
            return
        }

        val oldestLoadedMessageId = currentState.messages
            .firstOrNull { !it.isPending }
            ?.id
        if (oldestLoadedMessageId == null) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "noNonPendingMessage",
                "conversationId" to conversationId,
                "messageCount" to currentState.messages.size,
            )
            return
        }

        Telemetry.event(
            "ChatHistoryPager", "loadAttempting",
            "conversationId" to conversationId,
            "beforeMessageId" to oldestLoadedMessageId,
            "currentMessageCount" to currentState.messages.size,
        )

        scope.launch {
            uiState.value = uiState.value.copy(isLoadingOlderMessages = true)
            try {
                val olderMessages = messageRepository.fetchOlderMessages(
                    agentId = agentId,
                    conversationId = conversationId,
                    beforeMessageId = oldestLoadedMessageId,
                )
                if (conversationId != activeConversationId()) {
                    Telemetry.event(
                        "ChatHistoryPager", "loadAbandoned",
                        "reason" to "conversationChanged",
                        "conversationId" to conversationId,
                    )
                    return@launch
                }

                val olderUi = olderMessages.toUiMessages()
                val mergedMessages = chatTimelineObserver.mergeOlderPage(
                    conversationId = conversationId,
                    olderMessages = olderUi,
                    existingMessages = uiState.value.messages,
                )
                val newHasMore = olderMessages.size >= MessageRepository.OLDER_MESSAGES_PAGE_SIZE
                uiState.value = uiState.value.copy(
                    messages = mergedMessages.toImmutableList(),
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = newHasMore,
                )
                Telemetry.event(
                    "ChatHistoryPager", "loadSucceeded",
                    "conversationId" to conversationId,
                    "fetchedCount" to olderMessages.size,
                    "mergedTotalCount" to mergedMessages.size,
                    "hasMoreAfter" to newHasMore,
                )
            } catch (e: Exception) {
                Telemetry.error(
                    "ChatHistoryPager", "loadFailed", e,
                    "conversationId" to conversationId,
                    "beforeMessageId" to oldestLoadedMessageId,
                )
                android.util.Log.w("AdminChatViewModel", "Failed to load older messages", e)
                if (conversationId == activeConversationId()) {
                    uiState.value = uiState.value.copy(isLoadingOlderMessages = false)
                }
            }
        }
    }
}
