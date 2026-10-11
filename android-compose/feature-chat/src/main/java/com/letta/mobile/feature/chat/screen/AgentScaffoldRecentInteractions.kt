package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import com.letta.mobile.ui.chat.surface.recents.RecentInteractionsMapping
import com.letta.mobile.ui.shell.sidebar.ShellRelativeTime
import kotlin.time.Clock

/**
 * letta-mobile-y5q9z: the canvas bubble's recent interactions on Android: the drawer's own list
 * (the agent's conversations from drawerConversationRepo, archived ones included) and the drawer's
 * own "open conversation" and "New chat" (onSwitchConversation). Null where the host cannot switch.
 */
@Composable
internal fun rememberAgentRecentInteractions(state: AgentScaffoldRuntimeState): ChatRecentInteractions? {
    val switch = state.params.navigation.onSwitchConversation ?: return null
    val agentId = state.agentIdValue
    val agentName = state.agentName.takeIf { it.isNotBlank() }
    val conversations = state.drawerConversations
    val openId = state.conversationId
    return remember(conversations, openId, switch, agentId, agentName) {
        // Relative times are taken when the list changes, as the drawer takes them when it opens.
        val now = Clock.System.now()
        ChatRecentInteractions(
            conversations = RecentInteractionsMapping.rows(
                conversations = conversations.toChatConversationSummaries(),
                openConversationId = openId,
                timeLabel = { ShellRelativeTime.compact(it, now) },
            ),
            onOpenConversation = { id -> switch(agentId, id, agentName) },
            onNewThread = { switch(agentId, null, agentName) },
        )
    }
}
