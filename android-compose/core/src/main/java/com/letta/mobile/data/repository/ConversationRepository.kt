package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationUpdateParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.letta.mobile.data.repository.api.IConversationRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationApi: ConversationApi,
    private val agentRepository: AgentRepository,
) : IConversationRepository {
    private val _conversationsByAgent = MutableStateFlow<Map<String, List<Conversation>>>(emptyMap())
    private val lastRefreshAtMillisByAgent = mutableMapOf<String, Long>()

    override fun getConversations(agentId: String): Flow<List<Conversation>> {
        return _conversationsByAgent.map { it[agentId] ?: emptyList() }
    }

    override suspend fun refreshConversations(agentId: String) {
        val conversations = conversationApi.listConversations(agentId = agentId)
        _conversationsByAgent.update { current -> current.toMutableMap().apply {
                    put(agentId, conversations)
                } }
        lastRefreshAtMillisByAgent[agentId] = System.currentTimeMillis()
    }

    fun getCachedConversations(agentId: String): List<Conversation> = _conversationsByAgent.value[agentId] ?: emptyList()

    fun hasFreshConversations(agentId: String, maxAgeMs: Long): Boolean {
        val lastRefreshAt = lastRefreshAtMillisByAgent[agentId] ?: return false
        return getCachedConversations(agentId).isNotEmpty() && System.currentTimeMillis() - lastRefreshAt <= maxAgeMs
    }

    suspend fun refreshConversationsIfStale(agentId: String, maxAgeMs: Long): Boolean {
        if (hasFreshConversations(agentId, maxAgeMs)) return false
        refreshConversations(agentId)
        return true
    }

    override suspend fun getConversation(id: String): Conversation {
        return conversationApi.getConversation(id)
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation {
        val params = ConversationCreateParams(agentId = agentId, summary = summary)
        val conversation = conversationApi.createConversation(params)
        _conversationsByAgent.update { current -> current.toMutableMap().apply {
                    put(agentId, listOf(conversation) + (get(agentId) ?: emptyList()).filterNot { it.id == conversation.id })
                } }
        lastRefreshAtMillisByAgent[agentId] = System.currentTimeMillis()
        return conversation
    }

    override suspend fun deleteConversation(id: String, agentId: String) {
        val snapshot = _conversationsByAgent.value[agentId] ?: emptyList()

        _conversationsByAgent.update { current -> current.toMutableMap().apply {
                    put(agentId, snapshot.filter { it.id != id })
                } }

        try {
            conversationApi.deleteConversation(id)
        } catch (e: Exception) {
            _conversationsByAgent.update { current -> current.toMutableMap().apply {
                        put(agentId, snapshot)
                    } }
            throw e
        }

    }

    override suspend fun updateConversation(id: String, agentId: String, summary: String) {
        val snapshot = _conversationsByAgent.value[agentId] ?: emptyList()
        val conversationIndex = snapshot.indexOfFirst { it.id == id }
        if (conversationIndex < 0) return

        val optimisticList = snapshot.toMutableList()
        optimisticList[conversationIndex] = snapshot[conversationIndex].copy(summary = summary)

        _conversationsByAgent.update { current -> current.toMutableMap().apply {
                    put(agentId, optimisticList)
                } }

        try {
            val params = ConversationUpdateParams(summary = summary)
            val updated = conversationApi.updateConversation(id, params)
            _conversationsByAgent.update { current -> current.toMutableMap().apply {
                        put(agentId, optimisticList.map { if (it.id == updated.id) updated else it })
                    } }
        } catch (e: Exception) {
            _conversationsByAgent.update { current -> current.toMutableMap().apply {
                        put(agentId, snapshot)
                    } }
            throw e
        }
    }

    override suspend fun setConversationArchived(id: String, agentId: String, archived: Boolean) {
        val snapshot = _conversationsByAgent.value[agentId] ?: emptyList()
        val conversationIndex = snapshot.indexOfFirst { it.id == id }
        if (conversationIndex < 0) return

        val optimisticList = snapshot.toMutableList()
        optimisticList[conversationIndex] = snapshot[conversationIndex].copy(archived = archived)

        _conversationsByAgent.update { current -> current.toMutableMap().apply {
                    put(agentId, optimisticList)
                } }

        try {
            val params = ConversationUpdateParams(archived = archived)
            val updated = conversationApi.updateConversation(id, params)
            _conversationsByAgent.update { current -> current.toMutableMap().apply {
                        put(agentId, optimisticList.map { if (it.id == updated.id) updated else it })
                    } }
        } catch (e: Exception) {
            _conversationsByAgent.update { current -> current.toMutableMap().apply {
                        put(agentId, snapshot)
                    } }
            throw e
        }
    }

    override suspend fun cancelConversation(id: String, agentId: String?) {
        conversationApi.cancelConversation(id, agentId)
    }

    override suspend fun recompileConversation(id: String, dryRun: Boolean, agentId: String?): String {
        return if (agentId != null && !dryRun) {
            var result = ""
            agentRepository.checkpointAndRestoreConfig(agentId) {
                result = conversationApi.recompileConversation(id, dryRun, agentId)
            }
            result
        } else {
            conversationApi.recompileConversation(id, dryRun, agentId)
        }
    }

    override suspend fun forkConversation(id: String, agentId: String): Conversation {
        val conversation = conversationApi.forkConversation(id, agentId)
        _conversationsByAgent.update { current -> current.toMutableMap().apply {
                    put(agentId, listOf(conversation) + (get(agentId) ?: emptyList()).filterNot { it.id == conversation.id })
                } }
        lastRefreshAtMillisByAgent[agentId] = System.currentTimeMillis()
        return conversation
    }
}
