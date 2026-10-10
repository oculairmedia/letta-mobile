package com.letta.mobile.ui.chat.surface.recents

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarks
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import com.letta.mobile.ui.shell.sidebar.ShellSidebarMapping

/**
 * letta-mobile-y5q9z: the agent's recent interactions, as the canvas bubble's "+" lists them: the
 * same conversations the agent panel (the drawer, the desktop sidebar) lists, recent and archived
 * alike, so the person can hop between them without leaving the canvas, or start a new thread.
 *
 * The host builds it from what its agent panel already reads ([RecentInteractionsMapping]) and
 * binds the two actions to the panel's own "open conversation" and "new chat". Null on a host
 * that has no conversations to offer: the bubble shows no "+".
 */
@Immutable
data class ChatRecentInteractions(
    /** This agent's conversations, already resolved for display; archived ones are shown muted. */
    val conversations: List<ShellConversationRowModel>,
    /** Opens [ShellConversationRowModel.id], as the agent panel's row does. */
    val onOpenConversation: (String) -> Unit,
    /** Starts a new conversation with the agent, as the agent panel's "New chat" does. */
    val onNewThread: () -> Unit,
)

/** Pure mapping from the hosts' conversation summaries to the recent interactions' rows. */
object RecentInteractionsMapping {
    /**
     * [conversations] as rows: the agent panel's own mapping under its "All" scope, so archived
     * (dismissed) conversations are listed beside the recent ones, pinned first. [openConversationId]
     * reads as the current one. A conversation listed twice (a server echo while a run is live) is
     * listed once.
     */
    fun rows(
        conversations: List<ChatConversationSummary>,
        openConversationId: String?,
        pinnedIds: Set<String> = emptySet(),
        timeLabel: (String) -> String,
    ): List<ShellConversationRowModel> =
        ShellSidebarMapping.conversationRows(
            // No archive filter: the panel's "All" scope.
            conversations = conversations.distinctBy { it.id },
            marks = ShellConversationMarks(selectedId = openConversationId, pinnedIds = pinnedIds),
            timeLabel = timeLabel,
        )
}
