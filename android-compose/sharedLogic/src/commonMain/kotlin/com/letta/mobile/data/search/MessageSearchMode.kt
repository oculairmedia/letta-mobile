package com.letta.mobile.data.search

import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.ParsedSearchMessage

/** How a host's message search matches text. [wire] is Letta's `search_mode`: `vector`, `fts` or `hybrid`. */
enum class MessageSearchMode(val wire: String, val label: String) {
    Hybrid("hybrid", "Hybrid"),
    Semantic("vector", "Semantic"),
    FullText("fts", "Full-text"),
}

/**
 * Which kinds of message a search keeps. Letta's `/v1/messages/search` takes no role filter, so
 * every host filters its hits on the client by their role.
 */
enum class MessageSearchRole(val id: String, val label: String) {
    User("user", "User"),
    Assistant("assistant", "Assistant"),
    Reasoning("reasoning", "Reasoning"),
    Tool("tool", "Tool"),
    ;

    companion object {
        val DEFAULT = setOf(User, Assistant)
        val ALL = entries.toSet()

        /** The role of a backend hit, from its Letta `role` or `message_type`; null for kinds no chip covers (system). */
        fun fromBackend(value: String?): MessageSearchRole? = when (value?.lowercase()) {
            "user", "user_message" -> User
            "assistant", "assistant_message" -> Assistant
            "reasoning_message" -> Reasoning
            "tool", "tool_call_message", "tool_return_message" -> Tool
            else -> null
        }
    }
}

/** The live mode and role chips for one search. Full-text by default: it needs no embeddings. */
data class MessageSearchSelection(
    val mode: MessageSearchMode = MessageSearchMode.FullText,
    val roles: Set<MessageSearchRole> = MessageSearchRole.DEFAULT,
) {
    fun effectiveRoles(): Set<MessageSearchRole> = roles.ifEmpty { MessageSearchRole.DEFAULT }

    /** Whether a hit of [role] is kept; a hit of no known role (system text) never is. */
    fun keeps(role: MessageSearchRole?): Boolean = role != null && role in effectiveRoles()

    fun toggled(role: MessageSearchRole): MessageSearchSelection {
        val next = if (role in roles) roles - role else roles + role
        return copy(roles = next.ifEmpty { MessageSearchRole.DEFAULT })
    }
}

/**
 * What a host can actually search. A host-side index is full-text only but sees every role; Letta's
 * message search has every mode and returns user, assistant and reasoning messages (no tool traffic).
 * The UI hides the mode row when [modes] is a singleton.
 */
data class MessageSearchCapabilities(
    val modes: Set<MessageSearchMode>,
    val roles: Set<MessageSearchRole>,
) {
    fun showsModes(): Boolean = modes.size > 1

    fun effectiveMode(requested: MessageSearchMode): MessageSearchMode =
        requested.takeIf { it in modes } ?: modes.firstOrNull() ?: MessageSearchMode.FullText

    companion object {
        val NONE = MessageSearchCapabilities(modes = emptySet(), roles = emptySet())
        val LOCAL = MessageSearchCapabilities(modes = setOf(MessageSearchMode.FullText), roles = MessageSearchRole.ALL)
        val REMOTE = MessageSearchCapabilities(
            modes = MessageSearchMode.entries.toSet(),
            roles = setOf(MessageSearchRole.User, MessageSearchRole.Assistant, MessageSearchRole.Reasoning),
        )
    }
}

/** Builds the backend request for [this] query, degrading [MessageSearchSelection.mode] to what [capabilities] supports. */
fun MessageSearchQuery.toRequest(
    capabilities: MessageSearchCapabilities,
    limit: Int = DEFAULT_SEARCH_LIMIT,
): MessageSearchRequest = MessageSearchRequest(
    query = text,
    searchMode = capabilities.effectiveMode(selection.mode).wire,
    agentId = agentId,
    limit = limit,
)

/**
 * Runs [this] query against Letta's message search through [search] (the host's
 * `IMessageRepository.searchMessages`, parsed) and keeps the hits the role chips select.
 */
suspend fun MessageSearchQuery.searchBackend(
    limit: Int = DEFAULT_SEARCH_LIMIT,
    search: suspend (MessageSearchRequest) -> List<ParsedSearchMessage>,
): List<ParsedSearchMessage> =
    search(toRequest(MessageSearchCapabilities.REMOTE, limit))
        .filter { selection.keeps(MessageSearchRole.fromBackend(it.role)) }

internal const val DEFAULT_SEARCH_LIMIT = 50
