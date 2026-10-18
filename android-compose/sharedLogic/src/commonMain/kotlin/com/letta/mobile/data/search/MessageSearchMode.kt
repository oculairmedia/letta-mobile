package com.letta.mobile.data.search

import com.letta.mobile.data.model.MessageSearchRequest

/** How a host's message search matches text. Wire values are the backend's `search_mode`. */
enum class MessageSearchMode(val wire: String, val label: String) {
    Hybrid("hybrid", "Hybrid"),
    Semantic("semantic", "Semantic"),
    FullText("fts", "Full-text"),
}

/** Which message roles a search includes. Wire values are the backend's `roles`. */
enum class MessageSearchRole(val wire: String, val label: String) {
    User("user", "User"),
    Assistant("assistant", "Assistant"),
    Reasoning("reasoning", "Reasoning"),
    Tool("tool", "Tool"),
    ;

    companion object {
        val DEFAULT = setOf(User, Assistant)
        val ALL = entries.toSet()

        fun fromWire(value: String?): MessageSearchRole = when (value?.lowercase()) {
            "assistant", "assistant_message" -> Assistant
            "reasoning", "reasoning_message" -> Reasoning
            "tool", "tool_call_message", "tool_return_message" -> Tool
            else -> User
        }
    }
}

/** The live mode and role chips for one search. */
data class MessageSearchSelection(
    val mode: MessageSearchMode = MessageSearchMode.Hybrid,
    val roles: Set<MessageSearchRole> = MessageSearchRole.DEFAULT,
) {
    fun effectiveRoles(): Set<MessageSearchRole> = roles.ifEmpty { MessageSearchRole.DEFAULT }

    fun toggled(role: MessageSearchRole): MessageSearchSelection {
        val next = if (role in roles) roles - role else roles + role
        return copy(roles = next.ifEmpty { MessageSearchRole.DEFAULT })
    }
}

/**
 * What a host can actually search. Local indexes are full-text only; backends that speak
 * `MessageSearchRequest` expose every mode. The UI hides the mode row when [modes] is a singleton.
 */
data class MessageSearchCapabilities(
    val modes: Set<MessageSearchMode>,
    val roles: Set<MessageSearchRole> = MessageSearchRole.ALL,
) {
    fun showsModes(): Boolean = modes.size > 1

    fun effectiveMode(requested: MessageSearchMode): MessageSearchMode =
        requested.takeIf { it in modes } ?: modes.firstOrNull() ?: MessageSearchMode.FullText

    companion object {
        val NONE = MessageSearchCapabilities(modes = emptySet(), roles = emptySet())
        val LOCAL = MessageSearchCapabilities(modes = setOf(MessageSearchMode.FullText))
        val REMOTE = MessageSearchCapabilities(modes = MessageSearchMode.entries.toSet())
    }
}

/** Builds the backend request for [this] query, degrading [MessageSearchSelection.mode] to what [capabilities] supports. */
fun MessageSearchQuery.toRequest(
    capabilities: MessageSearchCapabilities,
    limit: Int = DEFAULT_SEARCH_LIMIT,
): MessageSearchRequest = MessageSearchRequest(
    query = text,
    searchMode = capabilities.effectiveMode(selection.mode).wire,
    roles = selection.effectiveRoles().map { it.wire },
    agentId = agentId,
    limit = limit,
)

internal const val DEFAULT_SEARCH_LIMIT = 50
