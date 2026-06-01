package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class ChatSessionResolver(
    private val agentRepository: IAgentRepository,
    private val conversationRepository: IConversationRepository,
    private val backgroundRefreshScope: CoroutineScope? = null,
) {
    fun cachedAgentName(agentId: String): String? {
        return agentRepository.getCachedAgent(AgentId(agentId))
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
            if (!conversationRepository.hasFreshConversations(AgentId(agentId), maxAgeMs)) {
                backgroundRefreshScope?.launch {
                    runCatching { conversationRepository.refreshConversationsIfStale(AgentId(agentId), maxAgeMs) }
                } ?: runCatching { conversationRepository.refreshConversationsIfStale(AgentId(agentId), maxAgeMs) }
            }
            return cachedConversationId
        }
        conversationRepository.refreshConversationsIfStale(AgentId(agentId), maxAgeMs)
        return mostRecentCachedConversationId(agentId)
    }

    private fun mostRecentCachedConversationId(agentId: String): String? {
        return conversationRepository.getCachedConversations(AgentId(agentId))
            .sortedByDescending { it.lastMessageAt ?: it.createdAt ?: "" }
            .firstOrNull()
            ?.id
            ?.value
    }
}
