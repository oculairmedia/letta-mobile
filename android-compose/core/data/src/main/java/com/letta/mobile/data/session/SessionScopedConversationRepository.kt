package com.letta.mobile.data.session

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IConversationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedConversationRepository @Inject constructor(
    private val sessionManager: SessionManager,
) : IConversationRepository, BackendScopedCache {
    private val current: IConversationRepository
        get() = sessionManager.current.conversationRepository

    override fun getConversations(agentId: AgentId): Flow<List<Conversation>> =
        sessionManager.currentGraph.flatMapLatest { it.conversationRepository.getConversations(agentId) }

    override fun getCachedConversations(agentId: AgentId): List<Conversation> =
        current.getCachedConversations(agentId)

    override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean =
        current.hasFreshConversations(agentId, maxAgeMs)

    override suspend fun refreshConversations(agentId: AgentId) =
        sessionManager.withCurrentSession { it.conversationRepository.refreshConversations(agentId) }

    override suspend fun clearForBackendSwitch() {
        sessionManager.current.conversationRepository.clearForBackendSwitch()
    }

    override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean =
        sessionManager.withCurrentSession { it.conversationRepository.refreshConversationsIfStale(agentId, maxAgeMs) }

    override suspend fun getConversation(id: ConversationId): Conversation =
        sessionManager.withCurrentSession { it.conversationRepository.getConversation(id) }

    override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation =
        sessionManager.withCurrentSession { it.conversationRepository.createConversation(agentId, summary) }

    override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) =
        sessionManager.withCurrentSession { it.conversationRepository.deleteConversation(id, agentId) }

    override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) =
        sessionManager.withCurrentSession { it.conversationRepository.updateConversation(id, agentId, summary) }

    override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) =
        sessionManager.withCurrentSession { it.conversationRepository.setConversationArchived(id, agentId, archived) }

    override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) =
        sessionManager.withCurrentSession { it.conversationRepository.cancelConversation(id, agentId) }

    override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String =
        sessionManager.withCurrentSession { it.conversationRepository.recompileConversation(id, dryRun, agentId) }

    override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation =
        sessionManager.withCurrentSession { it.conversationRepository.forkConversation(id, agentId) }
}
