package com.letta.mobile.data.model

/** Native conversation and optional owner. Rejects ambiguous bare defaults; preserves non-aliases. */
fun resolveConversationReadTarget(conversationId: ConversationId): Pair<ConversationId, AgentId?>? {
    val id = conversationId.value
    if (id.isEmpty() || id == IdFormats.DEFAULT_CONVERSATION_ID) return null
    val owner = defaultAliasOwner(id) ?: return conversationId to null
    return ConversationId(IdFormats.DEFAULT_CONVERSATION_ID) to owner
}

/** The agent a `conv-default-<agentId>` alias names, or null when [id] is not such an alias. */
private fun defaultAliasOwner(id: String): AgentId? {
    if (!IdFormats.hasDefaultConversationPrefix(id)) return null
    val agent = id.removePrefix(IdFormats.DEFAULT_CONVERSATION_PREFIX)
    return AgentId(agent).takeIf { agent.length > IdFormats.AGENT_PREFIX.length && agent.startsWith(IdFormats.AGENT_PREFIX) }
}
