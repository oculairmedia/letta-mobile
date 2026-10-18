package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.search.MessageSearchCapabilities
import com.letta.mobile.data.search.MessageSearchSelection
import com.letta.mobile.ui.components.searchResultSnippet
import com.letta.mobile.ui.search.LettaSearchConfig
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchSection
import com.letta.mobile.ui.search.messageSearchChipRows
import com.letta.mobile.util.formatRelativeTime

/**
 * The chat header's message search (hard cut S9): the agent's message hits as the shared
 * [com.letta.mobile.ui.shell.header.SearchSlot]'s rows, in place of the legacy results pane that
 * replaced the timeline. Picking a row opens its conversation.
 */
internal object ChatMessageSearch {
    const val SECTION_TITLE = "Messages"

    /** The pane's config; its chips are Letta's message search modes and roles, live in [selection]. */
    fun config(
        placeholder: String,
        isSearching: Boolean,
        selection: MessageSearchSelection,
        onSelection: (MessageSearchSelection) -> Unit,
    ) = LettaSearchConfig(
        placeholder = placeholder,
        // The header field is always present; it must not take the caret from the composer.
        autoFocus = false,
        emptyText = { query ->
            when {
                isSearching -> "Searching..."
                query.isBlank() -> "Search this agent's messages"
                else -> "No results for \"$query\""
            }
        },
        chipRows = messageSearchChipRows(MessageSearchCapabilities.REMOTE, selection, onSelection),
    )

    fun sections(
        query: String,
        results: List<ParsedSearchMessage>,
        conversations: List<Conversation>,
        currentConversationId: String?,
    ): List<LettaSearchSection> {
        if (results.isEmpty()) return emptyList()
        val conversationsById = conversations.associateBy { it.id.value }
        val rows = results.mapIndexed { index, result ->
            val summary = result.conversationId?.let(conversationsById::get)?.summary?.takeIf { it.isNotBlank() }
            val scope = when {
                result.conversationId != null && result.conversationId == currentConversationId -> "Current conversation"
                result.conversationId != null -> "Previous conversation"
                else -> "Conversation unknown"
            }
            LettaSearchRow(
                id = chatSearchResultKey(result, index),
                label = searchResultSnippet(result.content.orEmpty(), query),
                sublabel = listOfNotNull(scope, summary).joinToString(" - "),
                meta = result.date?.let(::formatRelativeTime)?.takeIf { it.isNotBlank() },
            )
        }
        return listOf(LettaSearchSection(SECTION_TITLE, rows))
    }

    /** The hit a picked row stands for. */
    fun resultFor(rowId: String, results: List<ParsedSearchMessage>): ParsedSearchMessage? =
        results.withIndex().firstOrNull { (index, result) -> chatSearchResultKey(result, index) == rowId }?.value
}

internal fun chatSearchResultKey(result: ParsedSearchMessage, index: Int): String {
    val identity = result.messageId
        ?: result.conversationId?.let { conversationId ->
            "$conversationId-${result.content.hashCode()}"
        }
        ?: result.content.hashCode().toString()
    return "chat-search-$identity-$index"
}
