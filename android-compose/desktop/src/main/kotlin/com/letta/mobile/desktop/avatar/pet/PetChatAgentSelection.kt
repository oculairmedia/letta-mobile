package com.letta.mobile.desktop.avatar.pet

import com.letta.mobile.desktop.chat.DesktopConversationSummary

/**
 * The agent the pet chats with, plus the human-readable name shown in the reply
 * popup header (PRD §5 B1: "show the chosen agent's name in the popup header").
 */
data class PetChatAgent(
    val agentId: String,
    val displayName: String,
)

/**
 * Pick the pet's target agent from the loaded conversation list (v1: no agent
 * switching UI — PRD §5 B1). The [DesktopChatController] lists conversations
 * newest-first, so the head of [conversations] is the most-recently-updated
 * conversation; its agent wins. We fall back to the first conversation that
 * carries any usable agent id if the newest one somehow lacks it (older history
 * predating agent-id tracking), and finally give up (returns null) when nothing
 * has an agent — the caller then keeps the popup disabled and surfaces the
 * reason in the status chip rather than sending into a void.
 *
 * A roster-agent fallback (an agent with zero conversations) is deferred: the
 * pet spike process doesn't stand up the full agent repository, and the common
 * case — the user's live backend — always has at least one conversation. If a
 * roster is wired in later, prepend its first agent as an additional fallback
 * after this returns null.
 */
fun selectPetChatAgent(conversations: List<DesktopConversationSummary>): PetChatAgent? {
    val withAgent = conversations.firstOrNull { !it.agentId.isNullOrBlank() } ?: return null
    val agentId = withAgent.agentId ?: return null
    val displayName = withAgent.agentName
        .trim()
        .takeIf { it.isNotBlank() }
        ?: agentId
    return PetChatAgent(agentId = agentId, displayName = displayName)
}
