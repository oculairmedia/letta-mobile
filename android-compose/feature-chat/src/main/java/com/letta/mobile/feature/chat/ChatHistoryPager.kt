package com.letta.mobile.feature.chat

import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
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

        // letta-mobile-doq50: prefer a real server message ID (user / assistant)
        // over synthetic IDs like `toolreturn-...` that the WS frame mapper
        // generates locally to dedup tool returns. The server doesn't
        // recognize synthetic IDs as cursors, so older-message fetches with
        // such an ID either silently no-op or return the same page that's
        // already loaded — which the merge then filters out, leaving the
        // visible message count unchanged.
        //
        // Fallback to the original "first non-pending" if no real ID is found
        // so the bug surface is bounded by content shape rather than crashing.
        val oldestLoadedMessageId = currentState.messages
            .firstOrNull { !it.isPending && it.isPaginationCursorEligible() }
            ?.id
            ?: currentState.messages.firstOrNull { !it.isPending }?.id
        if (oldestLoadedMessageId == null) {
            Telemetry.event(
                "ChatHistoryPager", "loadSkipped",
                "reason" to "noNonPendingMessage",
                "conversationId" to conversationId,
                "messageCount" to currentState.messages.size,
            )
            return
        }

        val cursorIsSynthetic = oldestLoadedMessageId.startsWith("toolreturn-")
        Telemetry.event(
            "ChatHistoryPager", "loadAttempting",
            "conversationId" to conversationId,
            "beforeMessageId" to oldestLoadedMessageId,
            "currentMessageCount" to currentState.messages.size,
            "cursorIsSynthetic" to cursorIsSynthetic,
        )

        scope.launch {
            uiState.value = uiState.value.copy(isLoadingOlderMessages = true)
            try {
                val olderMessages = messageRepository.fetchOlderMessages(
                    agentId = AgentId(agentId),
                    conversationId = ConversationId(conversationId),
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

                val previousCount = uiState.value.messages.size
                val olderUi = olderMessages.toUiMessages()
                val mergedMessages = chatTimelineObserver.mergeOlderPage(
                    conversationId = conversationId,
                    olderMessages = olderUi,
                    existingMessages = uiState.value.messages,
                )
                // letta-mobile-doq50: if a fetch returns messages but the
                // merge filters all of them as duplicates of what's already
                // loaded, the cursor is broken (server returned the same
                // page). Mark pagination terminal to prevent an infinite
                // spinner-then-clear loop. The user can still pull-to-
                // refresh to retry.
                val mergeAddedMessages = mergedMessages.size > previousCount
                val newHasMore = mergeAddedMessages &&
                    olderMessages.size >= MessageRepository.OLDER_MESSAGES_PAGE_SIZE
                uiState.value = uiState.value.copy(
                    messages = mergedMessages.toImmutableList(),
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = newHasMore,
                )
                Telemetry.event(
                    "ChatHistoryPager", "loadSucceeded",
                    "conversationId" to conversationId,
                    "fetchedCount" to olderMessages.size,
                    "mergedAddedCount" to (mergedMessages.size - previousCount),
                    "mergedTotalCount" to mergedMessages.size,
                    "hasMoreAfter" to newHasMore,
                    "filteredAllDuplicates" to (olderMessages.isNotEmpty() && !mergeAddedMessages),
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
