package com.letta.mobile.data.context

import com.letta.mobile.data.chat.runtime.SharedChatSessionResolver

/** The conversation a context reading belongs to. */
data class ContextReadingKey(
    val agentId: String,
    val conversationId: String,
)

/**
 * The key a reading is stored and looked up under — the ONE place both sides (the frame
 * writing it, the chip reading it) name a conversation.
 *
 * An agent's default conversation has two spellings: the App Server's bare `default`, and the
 * app's addressable `conv-default-<agentId>` (what conversation lists, routes and the send
 * coordinator use). Both map to the app's form, so a frame stamped either way reaches the chip.
 * Null when either half is blank.
 */
fun contextReadingKeyOf(agentId: String?, conversationId: String?): ContextReadingKey? {
    val agent = agentId?.takeIf { it.isNotBlank() } ?: return null
    val conversation = conversationId?.takeIf { it.isNotBlank() } ?: return null
    val canonical = if (conversation == BARE_DEFAULT_CONVERSATION) "$DEFAULT_CONVERSATION_PREFIX$agent" else conversation
    return ContextReadingKey(agent, canonical)
}

/** The reading for one conversation, or null when either half of its identity is unknown. */
fun Map<ContextReadingKey, Int>.readingFor(agentId: String?, conversationId: String?): Int? =
    contextReadingKeyOf(agentId, conversationId)?.let(::get)

private const val BARE_DEFAULT_CONVERSATION = "default"
private const val DEFAULT_CONVERSATION_PREFIX = SharedChatSessionResolver.DEFAULT_SHIM_CONVERSATION_PREFIX
