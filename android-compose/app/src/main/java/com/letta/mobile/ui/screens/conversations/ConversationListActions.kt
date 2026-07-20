package com.letta.mobile.ui.screens.conversations

internal data class ConversationListActions(
    val onConversationClick: (ConversationDisplay) -> Unit,
    val onOpenAdmin: (ConversationDisplay) -> Unit,
    val onDeleteConversation: (ConversationDisplay) -> Unit,
    val onRenameConversation: (ConversationDisplay, String) -> Unit,
    val onTogglePinned: (ConversationDisplay) -> Unit,
    val onForkConversation: (ConversationDisplay) -> Unit,
    val onRefresh: () -> Unit,
)
