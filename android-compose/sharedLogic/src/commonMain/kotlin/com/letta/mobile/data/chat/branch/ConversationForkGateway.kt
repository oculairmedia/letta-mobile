package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.Conversation

/**
 * letta-mobile-bzvro.15 (F15): what a fork asks the backend for.
 *
 * [throughMessageId] is inclusive: the fork keeps every message up to and including it. Null
 * forks the whole conversation. [agentId] names the agent the fork belongs to; backends use it
 * for the agent-direct `default` conversation and ignore it otherwise.
 */
data class ConversationForkRequest(
    val conversationId: String,
    val agentId: String? = null,
    val throughMessageId: String? = null,
)

/**
 * Optional gateway capability: fork a conversation on the backend that owns it. App Server and
 * Iroh backends send `conversation_fork` (directly or through the host's `conversation.fork`
 * admin RPC); REST backends call `POST /v1/conversations/{id}/fork`. Callers probe for it with
 * `gateway as? ConversationForkGateway`, the way they probe for the other optional capabilities.
 */
interface ConversationForkGateway {
    /** The new conversation, fully read back (upstream answers a fork with its id only). */
    suspend fun forkConversation(request: ConversationForkRequest): Conversation
}
