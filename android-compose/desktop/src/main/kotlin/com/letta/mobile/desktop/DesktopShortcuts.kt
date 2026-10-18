package com.letta.mobile.desktop

import com.letta.mobile.data.chat.runtime.ConversationListGroups
import com.letta.mobile.data.chat.runtime.ConversationListOrder
import com.letta.mobile.desktop.chat.DesktopConversationSummary

/**
 * Sidebar list order (pinned first, settled last) so prev/next conversation matches the panel.
 * Binding only — the keymap itself lives in sharedUI (letta-mobile-k5nbq.4).
 */
internal fun desktopConversationOrder(
    conversations: List<DesktopConversationSummary>,
    groups: ConversationListGroups,
): List<String> = ConversationListOrder.arrange(conversations, groups) { it.id }.map { it.id }
