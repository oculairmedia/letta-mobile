package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.ui.chat.render.ChatUiState

/** Handles older-message backfill without letting live timeline emissions drop the prefix. */
internal class ChatHistoryPager(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val messageRepository: MessageRepository,
    private val chatTimelineObserver: ChatTimelineObserver,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val activeConversationId: () -> String?,
    private val selectionGeneration: () -> Long,
) {
    private data class RequestOwner(
        val conversationId: String,
        val selectionGeneration: Long,
        val requestId: Long,
    )

    private val ownershipLock = Any()
    private var activeOwner: RequestOwner? = null
    private var nextRequestId = 0L

    fun loadOlderMessages(clientModeEnabled: Boolean) {
        val request = classifyLoadRequest(clientModeEnabled) ?: return
        val owner = claimOwner(request) ?: return
        reportLoadAttempt(owner, request)
        scope.launch { fetchAndApply(owner, request) }
    }

    private data class LoadRequest(
        val conversationId: String,
        val generation: Long,
        val state: ChatUiState,
        val beforeMessageId: String,
    )

    private fun classifyLoadRequest(clientModeEnabled: Boolean): LoadRequest? {
        if (clientModeEnabled) return skipLoadForAgent("clientModeEnabled")
        val conversationId = activeConversationId()
            ?: return skipLoadForAgent("noActiveConversation")
        val state = uiState.value
        if (state.isLoadingMessages) return skipLoadForConversation("isLoadingMessages", conversationId)
        if (!state.hasMoreOlderMessages) {
            return skipLoadForConversation("noMoreOlder", conversationId, state.messages.size)
        }
        if (state.isStreaming) return skipLoadForConversation("isStreaming", conversationId)
        val beforeMessageId = state.messages
            .firstOrNull { !it.isPending && it.isPaginationCursorEligible() }
            ?.id
            ?: state.messages.firstOrNull { !it.isPending }?.id
            ?: return skipLoadForConversation("noNonPendingMessage", conversationId, state.messages.size)
        return LoadRequest(conversationId, selectionGeneration(), state, beforeMessageId)
    }

    private fun skipLoadForAgent(reason: String): Nothing? {
        Telemetry.event(
            "ChatHistoryPager", "loadSkipped",
            "reason" to reason,
            "agentId" to agentId,
        )
        return null
    }

    private fun skipLoadForConversation(
        reason: String,
        conversationId: String,
        messageCount: Int? = null,
    ): Nothing? {
        if (messageCount == null) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to reason,
                "conversationId" to conversationId,
            )
        } else {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to reason,
                "conversationId" to conversationId,
                "messageCount" to messageCount,
            )
        }
        return null
    }

    private fun claimOwner(request: LoadRequest): RequestOwner? = synchronized(ownershipLock) {
        val existingOwner = activeOwner
        if (existingOwner?.conversationId == request.conversationId &&
            existingOwner.selectionGeneration == request.generation
        ) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "alreadyLoadingOlder",
                "conversationId" to request.conversationId,
                "selectionGeneration" to request.generation,
                "requestId" to existingOwner.requestId,
            )
            null
        } else {
            RequestOwner(request.conversationId, request.generation, ++nextRequestId).also { activeOwner = it }
        }
    }

    private fun reportLoadAttempt(owner: RequestOwner, request: LoadRequest) {
        Telemetry.event(
            "ChatHistoryPager", "loadAttempting",
            "conversationId" to request.conversationId,
            "beforeMessageId" to request.beforeMessageId,
            "currentMessageCount" to request.state.messages.size,
            "cursorIsSynthetic" to request.beforeMessageId.startsWith("toolreturn-"),
            "selectionGeneration" to owner.selectionGeneration,
            "requestId" to owner.requestId,
        )
    }

    private suspend fun fetchAndApply(owner: RequestOwner, request: LoadRequest) {
        updateIfOwner(owner) { it.copy(isLoadingOlderMessages = true) }
        try {
            val olderPage = messageRepository.fetchOlderMessagesPage(
                agentId = AgentId(agentId),
                conversationId = ConversationId(request.conversationId),
                beforeMessageId = request.beforeMessageId,
            )
            applySuccessIfOwner(owner, request, olderPage.messages, olderPage.hasMore)
        } catch (e: CancellationException) {
            clearIfOwner(owner)
            throw e
        } catch (e: Exception) {
            Telemetry.error(
                "ChatHistoryPager", "loadFailed", e,
                "conversationId" to request.conversationId,
                "beforeMessageId" to request.beforeMessageId,
                "selectionGeneration" to owner.selectionGeneration,
                "requestId" to owner.requestId,
            )
            android.util.Log.w("AdminChatViewModel", "Failed to load older messages", e)
            clearIfOwner(owner)
        }
    }

    private fun applySuccessIfOwner(
        owner: RequestOwner,
        request: LoadRequest,
        olderMessages: List<AppMessage>,
        hasMore: Boolean?,
    ) {
        synchronized(ownershipLock) {
            if (!isCurrentOwner(owner)) {
                Telemetry.event(
                    "ChatHistoryPager", "loadAbandoned",
                    "reason" to "selectionChanged",
                    "conversationId" to request.conversationId,
                    "selectionGeneration" to owner.selectionGeneration,
                    "requestId" to owner.requestId,
                )
                return
            }
            val state = uiState.value
            val previousCount = state.messages.size
            val mergedMessages = chatTimelineObserver.mergeOlderPage(
                conversationId = request.conversationId,
                olderMessages = olderMessages.toUiMessages(),
                existingMessages = state.messages,
            )
            val mergeAddedMessages = mergedMessages.size > previousCount
            val newHasMore = mergeAddedMessages &&
                (hasMore ?: (olderMessages.size >= MessageRepository.OLDER_MESSAGES_PAGE_SIZE))
            uiState.value = state.copy(
                messages = mergedMessages.toImmutableList(),
                messageListChange = ChatMessageListChange.Full,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = newHasMore,
            )
            activeOwner = null
            Telemetry.event(
                "ChatHistoryPager", "loadSucceeded",
                "conversationId" to request.conversationId,
                "fetchedCount" to olderMessages.size,
                "mergedAddedCount" to (mergedMessages.size - previousCount),
                "mergedTotalCount" to mergedMessages.size,
                "hasMoreAfter" to newHasMore,
                "filteredAllDuplicates" to (olderMessages.isNotEmpty() && !mergeAddedMessages),
                "selectionGeneration" to owner.selectionGeneration,
                "requestId" to owner.requestId,
            )
        }
    }

    private fun isCurrentOwner(owner: RequestOwner): Boolean =
        activeOwner == owner &&
            activeConversationId() == owner.conversationId &&
            selectionGeneration() == owner.selectionGeneration

    private fun updateIfOwner(owner: RequestOwner, update: (ChatUiState) -> ChatUiState) {
        synchronized(ownershipLock) {
            if (isCurrentOwner(owner)) uiState.value = update(uiState.value)
        }
    }

    private fun clearIfOwner(owner: RequestOwner) {
        synchronized(ownershipLock) {
            if (isCurrentOwner(owner)) {
                uiState.value = uiState.value.copy(isLoadingOlderMessages = false)
                activeOwner = null
            }
        }
    }

    /**
     * Sliding-window release: called when the user scrolls back toward the
     * live tail after having pulled in older pages, so the resident window
     * shrinks again instead of only ever growing across a chat session.
     * Older content is never lost — it's re-fetched via [loadOlderMessages]
     * the next time the user scrolls back up, using the standard cursor
     * derived from whatever is now the oldest resident message.
     */
    fun releaseOlderMessages() {
        val conversationId = activeConversationId() ?: return
        val current = uiState.value
        val released = chatTimelineObserver.releaseOlderMessages(conversationId, current.messages)
        if (released.size == current.messages.size) return
        Telemetry.event(
            "ChatHistoryPager", "releaseOlder",
            "conversationId" to conversationId,
            "beforeCount" to current.messages.size,
            "afterCount" to released.size,
        )
        uiState.value = current.copy(
            messages = released.toImmutableList(),
            messageListChange = ChatMessageListChange.Full,
        )
    }
}

/**
 * letta-mobile-doq50: a UiMessage is eligible as a pagination cursor only if
 * its [UiMessage.id] is a real server-issued ID, not a locally-synthesized
 * one (like the `toolreturn-` prefix the WS frame mapper applies to dedup
 * tool returns into their corresponding tool call). The server only
 * recognizes its own message IDs as `before` cursors; passing a synthetic
 * ID silently returns the wrong page.
 */
private fun UiMessage.isPaginationCursorEligible(): Boolean {
    val id = id
    // Known synthetic prefixes used by the mobile client to construct
    // stable local IDs. Extend this list if other synthetic schemes appear.
    return !id.startsWith("toolreturn-")
}
