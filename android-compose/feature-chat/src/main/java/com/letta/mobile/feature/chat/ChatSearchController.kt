package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.toParsed
import com.letta.mobile.data.repository.MessageRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
internal class ChatSearchController(
    private val messageRepository: MessageRepository,
) {
    fun localResults(
        query: String,
        state: ChatUiState,
        agentId: String,
        conversationId: String?,
    ): ImmutableList<ParsedSearchMessage> = localResults(
        query = query,
        messages = state.messages,
        agentId = agentId,
        conversationId = conversationId,
    )

    fun localResults(
        query: String,
        messages: List<UiMessage>,
        agentId: String,
        conversationId: String?,
    ): ImmutableList<ParsedSearchMessage> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return persistentListOf()
        return messages
            .asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { it.content.contains(trimmedQuery, ignoreCase = true) }
            .map { message ->
                ParsedSearchMessage(
                    messageId = message.id,
                    agentId = agentId,
                    role = message.role,
                    content = message.content,
                    date = message.timestamp,
                    conversationId = conversationId,
                )
            }
            .toList()
            .toImmutableList()
    }

    suspend fun remoteResults(query: String, agentId: String): List<ParsedSearchMessage> {
        return messageRepository.searchMessages(
            MessageSearchRequest(
                query = query,
                searchMode = "fts",
                roles = listOf("user", "assistant"),
                agentId = agentId,
                limit = 50,
            )
        )
            .map { it.toParsed() }
            .filter { it.agentId == agentId }
    }

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
