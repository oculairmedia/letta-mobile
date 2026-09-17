package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState

/**
 * Keeps the conversation tab strip in step with the chat surface: the selected conversation gets a
 * tab when the Conversations destination shows it, and once the gateway has reported its
 * conversations, tabs for ones that no longer exist are dropped. Pure reducer calls, wired here
 * so the shell composable only owns the state.
 */
@Composable
internal fun SyncConversationTabs(
    chatState: DesktopChatSurfaceState,
    selectedDestination: DesktopDestination,
    tabs: ConversationTabsState,
    onTabsChange: (ConversationTabsState) -> Unit,
) {
    LaunchedEffect(selectedDestination, chatState.selectedConversationId) {
        val selectedId = chatState.selectedConversationId ?: return@LaunchedEffect
        val showing = selectedDestination == DesktopDestination.Conversations
        if (showing && selectedId !in tabs.openConversationIds) {
            onTabsChange(ConversationTabsReducer.select(tabs, selectedId))
        }
    }
    LaunchedEffect(chatState.connectionState, chatState.conversations) {
        if (chatState.connectionState.reportedConversations) {
            val availableIds = chatState.conversations.mapTo(mutableSetOf()) { it.id }
            onTabsChange(
                ConversationTabsReducer.retainAvailable(
                    state = tabs,
                    availableConversationIds = availableIds,
                    selectedConversationId = chatState.selectedConversationId,
                ),
            )
        }
    }
}

/** Live and NoConversations are the states in which the gateway's conversation list is authoritative. */
private val DesktopChatConnectionState.reportedConversations: Boolean
    get() = this == DesktopChatConnectionState.Live || this == DesktopChatConnectionState.NoConversations
