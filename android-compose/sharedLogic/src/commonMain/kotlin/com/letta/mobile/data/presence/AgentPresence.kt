package com.letta.mobile.data.presence

import com.letta.mobile.data.chat.runtime.ChatConversationSummary

/** What an agent is doing right now, as the UI can tell. Feeds the avatar director on every platform. */
enum class AgentActivityKind { IDLE, THINKING, SPEAKING }

/**
 * One agent's presence. Platform-neutral on purpose: the avatar director (avatar/core) turns this
 * into a mascot state with its own arbitration and timing; surfaces never map it themselves.
 */
data class AgentPresence(
    val activity: AgentActivityKind = AgentActivityKind.IDLE,
    /** A tool approval is parked in one of this agent's conversations. */
    val awaitingApproval: Boolean = false,
    /** The user is composing to this agent. */
    val userTyping: Boolean = false,
    /** The last attempt in one of this agent's conversations failed. */
    val error: Boolean = false,
) {
    companion object {
        val IDLE = AgentPresence()
    }
}

/**
 * Derives per-agent presence from the chat runtime's coarse signals.
 *
 * The one rule that matters: an agent is busy from the moment a turn is sent until that turn's
 * terminal frame, whatever happens in between - a first token, a tool call, a second reply.
 * `runningConversationId` is that whole-run signal (the controller keeps it until the terminal),
 * and `streamingTokens` only decides whether "busy" reads as speaking or thinking. Deriving
 * "thinking" from the reply landing is what made the indicator drop mid-run.
 */
object AgentPresenceResolver {
    fun resolve(
        conversations: List<ChatConversationSummary>,
        runningConversationId: String?,
        streamingTokens: Boolean,
        selectedConversationId: String?,
        composerText: String,
        approvalConversationIds: Set<String> = emptySet(),
        errorConversationId: String? = null,
    ): Map<String, AgentPresence> {
        val agentOf = conversations.associate { it.id to it.agentId }
        val out = HashMap<String, AgentPresence>()
        fun update(conversationId: String?, f: (AgentPresence) -> AgentPresence) {
            val agent = conversationId?.let { agentOf[it] } ?: return
            out[agent] = f(out[agent] ?: AgentPresence.IDLE)
        }
        update(runningConversationId) { it.copy(activity = if (streamingTokens) AgentActivityKind.SPEAKING else AgentActivityKind.THINKING) }
        approvalConversationIds.forEach { id -> update(id) { it.copy(awaitingApproval = true) } }
        if (composerText.isNotBlank()) update(selectedConversationId) { it.copy(userTyping = true) }
        update(errorConversationId) { it.copy(error = true) }
        return out
    }
}
