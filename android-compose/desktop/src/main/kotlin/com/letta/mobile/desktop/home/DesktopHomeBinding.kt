package com.letta.mobile.desktop.home

import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.home.FleetConversation
import com.letta.mobile.desktop.chat.DesktopConversationSummary

/**
 * The fleet model's view of a desktop conversation row: what the desktop feeds the shared Home
 * binding (letta-mobile-ckmub.17.5), whose shortcuts and controller are sharedUI's.
 */
internal fun DesktopConversationSummary.toFleetConversation(): FleetConversation = FleetConversation(
    id = id,
    agentId = agentId,
    agentName = agentName,
    title = displayTitle(),
    preview = lastMessagePreview,
    updatedAtLabel = updatedAtLabel,
)
