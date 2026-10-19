package com.letta.mobile.data.model

/**
 * The one place that knows the string shapes of Letta ids.
 *
 * Code that needs to recognise or build a special id goes through here instead of repeating a
 * literal such as `"conv-default-"` or `"cm-"`. The architecture-tests id-literal ratchet
 * (`id-literal-baseline.txt`) counts the literals that still live elsewhere and fails when a new
 * one appears; see docs/architecture/typed-ids-and-id-ratchets.md.
 */
object IdFormats {
    /** The bare conversation id the App Server uses for an agent's default conversation. */
    const val DEFAULT_CONVERSATION_ID: String = "default"

    /** External form of an agent's default conversation: `conv-default-<agentId>`. */
    const val DEFAULT_CONVERSATION_PREFIX: String = "conv-default-"

    /** Optimistic message id minted by the client for an outgoing message (`newOtid`). */
    const val CLIENT_MESSAGE_PREFIX: String = "client-"

    /** Optimistic message id minted for composer/stream rows (`cm-stream-*`, `cm-reason-*`). */
    const val COMPOSER_MESSAGE_PREFIX: String = "cm-"

    /** Prefix the server puts on agent ids (`agent-<uuid>`). */
    const val AGENT_PREFIX: String = "agent-"

    /** Every prefix that marks a message id as client-side and not yet server-confirmed. */
    val OPTIMISTIC_PREFIXES: List<String> = listOf(CLIENT_MESSAGE_PREFIX, COMPOSER_MESSAGE_PREFIX)

    /** `conv-default-<agentId>`: the external id of [agentId]'s default conversation. */
    fun defaultConversationIdFor(agentId: String): String = DEFAULT_CONVERSATION_PREFIX + agentId

    /** True for the external `conv-default-<agentId>` form only (not the bare `default`). */
    fun hasDefaultConversationPrefix(id: String?): Boolean =
        id != null && id.startsWith(DEFAULT_CONVERSATION_PREFIX)

    /** True for either default-conversation form: bare `default` or `conv-default-<agentId>`. */
    fun isDefaultConversation(id: String?): Boolean =
        id == DEFAULT_CONVERSATION_ID || hasDefaultConversationPrefix(id)

    fun isDefaultConversation(id: ConversationId): Boolean = isDefaultConversation(id.value)

    /** True when [id] carries one of [OPTIMISTIC_PREFIXES]; null is never optimistic. */
    fun isOptimisticMessageId(id: String?): Boolean =
        id != null && OPTIMISTIC_PREFIXES.any { id.startsWith(it) }

    fun isOptimisticMessageId(id: MessageId): Boolean = isOptimisticMessageId(id.value)

    /** Drops one leading [AGENT_PREFIX]; any other id is returned unchanged. */
    fun stripAgentPrefix(id: String): String = id.removePrefix(AGENT_PREFIX)

    fun stripAgentPrefix(id: AgentId): String = stripAgentPrefix(id.value)
}
