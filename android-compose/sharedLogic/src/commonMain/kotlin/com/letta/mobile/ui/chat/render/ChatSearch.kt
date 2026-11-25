package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.search.MessageSearchQuery
import com.letta.mobile.data.search.MessageSearchRole
import com.letta.mobile.data.search.searchBackend
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/*
 * The chat search fields of [ChatUiState] are shared, and their pure projections live here so
 * every session port answers a query the same way: the loaded timeline's own matches immediately,
 * then Letta's message search merged on top. The state itself ([ChatUiState.searchResults]) is
 * per-host, the matching is not.
 */

/** The timeline's visible [messages] a chat [query] shows: role chips keep them and its text matches. */
fun chatSearchLocalResults(
    agentId: AgentId,
    conversationId: ConversationId,
    query: String,
    messages: List<UiMessage>,
): ImmutableList<ParsedSearchMessage> {
    val asked = MessageSearchQuery(text = query, agentId = agentId.value)
    val text = asked.text.trim()
    if (text.isEmpty()) return persistentListOf()
    return messages
        .asSequence()
        .filter { asked.selection.keeps(MessageSearchRole.fromBackend(it.role)) }
        .filter { it.content.contains(text, ignoreCase = true) }
        .map { it.asChatSearchHit(agentId, conversationId) }
        .toImmutableList()
}

/** [this] message as the search field's local hit of the [agentId] whose conversation is on screen. */
private fun UiMessage.asChatSearchHit(agentId: AgentId, conversationId: ConversationId) = ParsedSearchMessage(
    messageId = id,
    agentId = agentId.value,
    role = role,
    content = content,
    date = timestamp,
    conversationId = conversationId.value,
)

/**
 * Letta's message search for a chat [query] over [search] (the host's message repository's answer,
 * parsed), kept to the query's agent and the roles its chips show.
 */
suspend fun chatSearchRemoteHits(
    agentId: AgentId,
    query: String,
    search: suspend (MessageSearchRequest) -> List<ParsedSearchMessage>,
): List<ParsedSearchMessage> = MessageSearchQuery(text = query, agentId = agentId.value)
    .searchBackend { request -> search(request) }

/** Local hits first, then remote ones, the first hit of each identity winning. */
fun mergeChatSearchHits(
    local: List<ParsedSearchMessage>,
    remote: List<ParsedSearchMessage>,
): ImmutableList<ParsedSearchMessage> = (local + remote).distinctBy { it.chatSearchIdentity() }.toImmutableList()

/** What makes a hit this hit: its message id, or every field of one without any. */
private fun ParsedSearchMessage.chatSearchIdentity(): String =
    messageId ?: listOfNotNull(agentId, conversationId, role, content).joinToString("|")
