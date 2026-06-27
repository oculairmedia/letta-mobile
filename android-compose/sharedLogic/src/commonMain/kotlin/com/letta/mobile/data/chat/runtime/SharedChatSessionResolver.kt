package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Platform-neutral resolution of the agent + conversation a chat session should
 * open with. This is the conversation-lifecycle logic that both Android and
 * desktop need at session start:
 *
 *  - resolving an agent's display name from the cache,
 *  - observing the cached name reactively, and
 *  - picking the most-recent real conversation to resume (skipping the
 *    default-shim placeholder), refreshing a stale cache as needed.
 *
 * It depends only on the shared [IAgentRepository] / [IConversationRepository]
 * contracts (already in commonMain) and kotlinx.coroutines, so it carries no
 * Android lifecycle, Dagger, or concrete-repository assumptions. Platform code
 * is expected to own only the wiring (DI, scopes) and to delegate the actual
 * resolution here.
 */
class SharedChatSessionResolver(
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
            .filterNot { it.id.value.startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX) }
            .sortedByDescending { it.lastMessageAt ?: it.createdAt ?: "" }
            .firstOrNull()
            ?.id
            ?.value
    }

    companion object {
        const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"
    }
}
