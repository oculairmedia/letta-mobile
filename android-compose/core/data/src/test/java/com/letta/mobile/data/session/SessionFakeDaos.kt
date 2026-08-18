package com.letta.mobile.data.session

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
