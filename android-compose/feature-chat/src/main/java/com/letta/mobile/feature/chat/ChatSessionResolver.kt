package com.letta.mobile.feature.chat

import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class ChatSessionResolver(
    private val agentRepository: AgentRepository,
    private val conversationRepository: ConversationRepository,
    private val backgroundRefreshScope: CoroutineScope? = null,
) {
    fun cachedAgentName(agentId: String): String? {
        return agentRepository.getCachedAgent(agentId)
            ?.name
            ?.takeIf { it.isNotBlank() }
    }

    fun observeCachedAgentName(agentId: String): Flow<String> {
        return agentRepository.agents
            .map { agents -> agents.firstOrNull { it.id.value == agentId }?.name.orEmpty() }
            .distinctUntilChanged()
    }

    suspend fun resolveMostRecentConversation(
        agentId: String,
        maxAgeMs: Long,
    ): String? {
        mostRecentCachedConversationId(agentId)?.let { cachedConversationId ->
            if (!conversationRepository.hasFreshConversations(agentId, maxAgeMs)) {
                backgroundRefreshScope?.launch {
                    runCatching { conversationRepository.refreshConversationsIfStale(agentId, maxAgeMs) }
                } ?: runCatching { conversationRepository.refreshConversationsIfStale(agentId, maxAgeMs) }
            }
            return cachedConversationId
        }
        conversationRepository.refreshConversationsIfStale(agentId, maxAgeMs)
        return mostRecentCachedConversationId(agentId)
    }

    private fun mostRecentCachedConversationId(agentId: String): String? {
        return conversationRepository.getCachedConversations(agentId)
            .sortedByDescending { it.lastMessageAt ?: it.createdAt ?: "" }
            .firstOrNull()
            ?.id
    }
}
