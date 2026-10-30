package com.letta.mobile.data.model

/** Native conversation and optional owner. Rejects ambiguous bare defaults; preserves non-aliases. */
fun resolveConversationReadTarget(conversationId: ConversationId): Pair<ConversationId, AgentId?>? {
    val id = conversationId.value
    if (id.isEmpty() || id == IdFormats.DEFAULT_CONVERSATION_ID) return null
    val agent = id.removePrefix(IdFormats.DEFAULT_CONVERSATION_PREFIX)
    return if (id != agent && agent.startsWith(IdFormats.AGENT_PREFIX) && agent.length > IdFormats.AGENT_PREFIX.length) {
        ConversationId(IdFormats.DEFAULT_CONVERSATION_ID) to AgentId(agent)
    } else {
        conversationId to null
    }
}
