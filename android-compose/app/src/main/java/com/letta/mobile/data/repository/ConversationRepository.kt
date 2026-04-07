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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationApi: ConversationApi,
) {
    private val _conversationsByAgent = MutableStateFlow<Map<String, List<Conversation>>>(emptyMap())

    fun getConversations(agentId: String): Flow<List<Conversation>> {
        return _conversationsByAgent.map { it[agentId] ?: emptyList() }
    }

    suspend fun refreshConversations(agentId: String) {
        val conversations = conversationApi.listConversations(agentId = agentId)
        _conversationsByAgent.value = _conversationsByAgent.value.toMutableMap().apply {
            put(agentId, conversations)
        }
    }

    suspend fun createConversation(agentId: String, summary: String? = null): Conversation {
        val params = ConversationCreateParams(agentId = agentId, summary = summary)
        val conversation = conversationApi.createConversation(params)
        refreshConversations(agentId)
        return conversation
    }

    suspend fun deleteConversation(id: String, agentId: String) {
        val snapshot = _conversationsByAgent.value[agentId] ?: emptyList()
        
        _conversationsByAgent.value = _conversationsByAgent.value.toMutableMap().apply {
            put(agentId, snapshot.filter { it.id != id })
        }

        try {
            conversationApi.deleteConversation(id)
        } catch (e: Exception) {
            _conversationsByAgent.value = _conversationsByAgent.value.toMutableMap().apply {
                put(agentId, snapshot)
            }
            throw e
        }

        refreshConversations(agentId)
    }

    suspend fun updateConversation(id: String, agentId: String, summary: String) {
        val snapshot = _conversationsByAgent.value[agentId] ?: emptyList()
        val conversationIndex = snapshot.indexOfFirst { it.id == id }
        if (conversationIndex < 0) return

        val optimisticList = snapshot.toMutableList()
        optimisticList[conversationIndex] = snapshot[conversationIndex].copy(summary = summary)
        
        _conversationsByAgent.value = _conversationsByAgent.value.toMutableMap().apply {
            put(agentId, optimisticList)
        }

        try {
            val params = ConversationUpdateParams(summary = summary)
            conversationApi.updateConversation(id, params)
        } catch (e: Exception) {
            _conversationsByAgent.value = _conversationsByAgent.value.toMutableMap().apply {
                put(agentId, snapshot)
            }
            throw e
        }

        refreshConversations(agentId)
    }

    suspend fun forkConversation(id: String, agentId: String): Conversation {
        val conversation = conversationApi.forkConversation(id, agentId)
        refreshConversations(agentId)
        return conversation
    }
}
