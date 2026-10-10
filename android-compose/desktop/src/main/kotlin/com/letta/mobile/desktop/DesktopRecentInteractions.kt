package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import com.letta.mobile.ui.chat.surface.recents.RecentInteractionsMapping

/**
 * letta-mobile-y5q9z: the docked chat's "+" on desktop: the sidebar's own conversations for the
 * focused agent (its stack grouping, under the "All" scope so archived ones are listed too) and the
 * sidebar's own "open conversation" and "New chat".
 */
@Composable
internal fun rememberDesktopRecentInteractions(context: DesktopShellContext, frame: DesktopShellFrame): ChatRecentInteractions {
    val focus = frame.focus
    val chatState = frame.chatState
    val activeSubagents = frame.lists.activeSubagents
    val pinned by context.core.chatController.conversationManagement.pinnedConversationIds.collectAsState()
    val selectedId = chatState.selectedConversationId
    val rows = remember(chatState.conversations, activeSubagents, focus.selectedAgentName, focus.selectedAgentId, selectedId, pinned) {
        RecentInteractionsMapping.rows(
            conversations = filterStackConversations(
                FilterStackConversationsParams(
                    conversations = chatState.conversations,
                    activeSubagents = activeSubagents,
                    selectedAgentName = focus.selectedAgentName,
                    selectedAgentId = focus.selectedAgentId,
                    selectedConversationId = selectedId,
                    archiveFilter = ConversationArchiveFilter.All,
                ),
            ),
            openConversationId = selectedId,
            pinnedIds = pinned,
            timeLabel = ::formatRelativeTimestamp,
        )
    }
    val router = context.router
    return ChatRecentInteractions(
        conversations = rows,
        onOpenConversation = { router.openConversation(ConversationId(it)) },
        onNewThread = { context.openNewChatForFocusedAgent(focus) },
    )
}
