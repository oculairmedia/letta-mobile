package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.toParsed
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.search.MessageSearchQuery
import com.letta.mobile.data.search.MessageSearchRole
import com.letta.mobile.data.search.searchBackend
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
internal class ChatSearchController(
    private val messageRepository: IMessageRepository,
) {
    /** The loaded timeline's messages matching [query] whose role its chips select. */
    fun localResults(
        query: MessageSearchQuery,
        messages: List<UiMessage>,
        conversationId: String?,
    ): ImmutableList<ParsedSearchMessage> {
        val trimmedQuery = query.text.trim()
        if (trimmedQuery.isBlank()) return persistentListOf()
        return messages
            .asSequence()
            .filter { query.selection.keeps(MessageSearchRole.fromBackend(it.role)) }
            .filter { it.content.contains(trimmedQuery, ignoreCase = true) }
            .map { message ->
                ParsedSearchMessage(
                    messageId = message.id,
                    agentId = query.agentId,
                    role = message.role,
                    content = message.content,
                    date = message.timestamp,
                    conversationId = conversationId,
                )
            }
            .toList()
            .toImmutableList()
    }

    /** Letta's message search for [query] (its mode and role chips), kept to [MessageSearchQuery.agentId]. */
    suspend fun remoteResults(query: MessageSearchQuery): List<ParsedSearchMessage> =
        query.searchBackend { request -> messageRepository.searchMessages(request).map { it.toParsed() } }
            .filter { it.agentId == query.agentId }

    fun mergeResults(
        local: List<ParsedSearchMessage>,
        remote: List<ParsedSearchMessage>,
    ): ImmutableList<ParsedSearchMessage> {
        val seen = LinkedHashSet<String>()
        return (local + remote)
            .filter { seen.add(it.searchIdentity()) }
            .toImmutableList()
    }

    private fun ParsedSearchMessage.searchIdentity(): String {
        return messageId
            ?: listOfNotNull(agentId, conversationId, role, content).joinToString("|")
    }
}
