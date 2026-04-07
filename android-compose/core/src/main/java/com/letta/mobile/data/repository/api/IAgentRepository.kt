package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IAgentRepository {
    val agents: StateFlow<List<Agent>>
    suspend fun refreshAgents()
    fun getAgent(id: String): Flow<Agent>
    suspend fun createAgent(params: AgentCreateParams): Agent
    suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent
    suspend fun deleteAgent(id: String)
    suspend fun exportAgent(id: String): String
}
