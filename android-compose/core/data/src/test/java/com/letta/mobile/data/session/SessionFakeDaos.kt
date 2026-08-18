package com.letta.mobile.data.session

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class FakeAgentDao : AgentDao {
    private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())

    override fun getAll(): Flow<List<AgentEntity>> = agents

    override suspend fun getAllOnce(): List<AgentEntity> = agents.value

    override suspend fun insertAll(agents: List<AgentEntity>) {
        this.agents.value = agents
    }

    override suspend fun upsert(agent: AgentEntity) {
        agents.value = agents.value.filterNot { it.id == agent.id } + agent
    }

    override suspend fun deleteExcept(keepIds: List<String>) {
        agents.value = agents.value.filter { it.id in keepIds }
    }

    override suspend fun deleteById(id: String) {
        agents.value = agents.value.filterNot { it.id == id }
    }

    override suspend fun deleteAll() {
        agents.value = emptyList()
    }
}

internal class FakeConversationDao : ConversationDao {
    val conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val refreshStates = mutableMapOf<String, ConversationRefreshEntity>()

    override fun observeForAgent(agentId: String): Flow<List<ConversationEntity>> =
        conversations.map { rows -> rows.filter { it.agentId == agentId } }

    override suspend fun getForAgentOnce(agentId: String): List<ConversationEntity> =
        conversations.value.filter { it.agentId == agentId }

    override suspend fun getAllOnce(): List<ConversationEntity> = conversations.value

    override suspend fun getByIdOnce(conversationId: String): ConversationEntity? =
        conversations.value.firstOrNull { it.id == conversationId }

    override suspend fun upsert(conversation: ConversationEntity) {
        conversations.value = conversations.value.filterNot { it.id == conversation.id } + conversation
    }

    override suspend fun upsertAll(conversations: List<ConversationEntity>) {
        conversations.forEach { upsert(it) }
    }

    override suspend fun delete(conversationId: String) {
        conversations.value = conversations.value.filterNot { it.id == conversationId }
    }

    override suspend fun deleteForAgent(agentId: String) {
        conversations.value = conversations.value.filterNot { it.agentId == agentId }
    }

    override suspend fun deleteForAgentExcept(agentId: String, keepIds: List<String>) {
        conversations.value = conversations.value.filterNot { it.agentId == agentId && it.id !in keepIds }
    }

    override suspend fun getRefreshState(agentId: String): ConversationRefreshEntity? = refreshStates[agentId]

    override suspend fun getAllRefreshStatesOnce(): List<ConversationRefreshEntity> = refreshStates.values.toList()

    override suspend fun upsertRefreshState(state: ConversationRefreshEntity) {
        refreshStates[state.agentId] = state
    }

    override suspend fun deleteAll() {
        conversations.value = emptyList()
    }

    override suspend fun deleteAllRefreshStates() {
        refreshStates.clear()
    }
}
