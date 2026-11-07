package com.letta.mobile.data.shell

import com.letta.mobile.data.home.conversationRecency
import com.letta.mobile.data.lanes.ConversationLane
import com.letta.mobile.data.lanes.ConversationPurpose
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/** What the shell knows of a conversation to route to it: whose it is and how recent. */
data class ShellConversationRef(
    val id: ConversationId,
    val agent: AgentId?,
    /** ISO-8601 update time, or a sentinel label ("Queued") for local rows. */
    val updatedAtLabel: String,
    val tags: List<String> = emptyList(),
) {
    val lane: ConversationLane
        get() = ConversationLane.fromTags(tags)
}

internal fun resolveChat(
    agent: AgentId,
    conversations: List<ShellConversationRef>,
    remembered: ConversationId?,
): ConversationId? {
    val own = conversations.filter { it.agent == agent }
    val home = own.firstOrNull { it.lane.purpose == ConversationPurpose.HOME }
    val history = remembered?.takeUnless { id -> own.any { it.id == id && !it.lane.isHumanFallback } }
    return home?.id ?: history ?: own.filter { it.lane.canTarget && it.lane.purpose != ConversationPurpose.INBOX }
        .maxByOrNull { conversationRecency(it.updatedAtLabel) }?.id
}
