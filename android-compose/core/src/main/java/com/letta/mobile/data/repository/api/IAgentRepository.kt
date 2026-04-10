package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IAgentRepository {
    val agents: StateFlow<List<Agent>>
    suspend fun countAgents(): Int
    suspend fun refreshAgents()
    fun getAgent(id: String): Flow<Agent>
    suspend fun createAgent(params: AgentCreateParams): Agent
    suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent
    suspend fun deleteAgent(id: String)
    suspend fun exportAgent(id: String): String
    suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String? = null,
        overrideExistingTools: Boolean? = null,
        projectId: String? = null,
        stripMessages: Boolean? = null,
    ): ImportedAgentsResponse
    suspend fun attachArchive(agentId: String, archiveId: String)
    suspend fun detachArchive(agentId: String, archiveId: String)
}
