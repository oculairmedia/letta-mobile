package com.letta.mobile.data.session

import com.letta.mobile.data.model.Conversation
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

    override fun getConversations(agentId: String): Flow<List<Conversation>> =
        sessionManager.currentGraph.flatMapLatest { it.conversationRepository.getConversations(agentId) }

    override fun getCachedConversations(agentId: String): List<Conversation> =
        current.getCachedConversations(agentId)

    override fun hasFreshConversations(agentId: String, maxAgeMs: Long): Boolean =
        current.hasFreshConversations(agentId, maxAgeMs)

    override suspend fun refreshConversations(agentId: String) =
        sessionManager.withCurrentSession { it.conversationRepository.refreshConversations(agentId) }

    override suspend fun clearForBackendSwitch() {
        sessionManager.current.conversationRepository.clearForBackendSwitch()
    }

    override suspend fun refreshConversationsIfStale(agentId: String, maxAgeMs: Long): Boolean =
        sessionManager.withCurrentSession { it.conversationRepository.refreshConversationsIfStale(agentId, maxAgeMs) }

    override suspend fun getConversation(id: String): Conversation =
        sessionManager.withCurrentSession { it.conversationRepository.getConversation(id) }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        sessionManager.withCurrentSession { it.conversationRepository.createConversation(agentId, summary) }

    override suspend fun deleteConversation(id: String, agentId: String) =
        sessionManager.withCurrentSession { it.conversationRepository.deleteConversation(id, agentId) }

    override suspend fun updateConversation(id: String, agentId: String, summary: String) =
        sessionManager.withCurrentSession { it.conversationRepository.updateConversation(id, agentId, summary) }

    override suspend fun setConversationArchived(id: String, agentId: String, archived: Boolean) =
        sessionManager.withCurrentSession { it.conversationRepository.setConversationArchived(id, agentId, archived) }

    override suspend fun cancelConversation(id: String, agentId: String?) =
        sessionManager.withCurrentSession { it.conversationRepository.cancelConversation(id, agentId) }

    override suspend fun recompileConversation(id: String, dryRun: Boolean, agentId: String?): String =
        sessionManager.withCurrentSession { it.conversationRepository.recompileConversation(id, dryRun, agentId) }

    override suspend fun forkConversation(id: String, agentId: String): Conversation =
        sessionManager.withCurrentSession { it.conversationRepository.forkConversation(id, agentId) }
}
