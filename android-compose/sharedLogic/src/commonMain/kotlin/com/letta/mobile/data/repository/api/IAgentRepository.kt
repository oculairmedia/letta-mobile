package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ImportedAgentsResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IAgentRepository {
    val agents: StateFlow<List<Agent>>
    /**
     * Set to true while [refreshAgents] is in flight (i.e. while the
     * agent cache may not reflect the active backend). Consumers that
     * need to gate "is this id an orphan?" decisions on a known-fresh
     * cache should observe this and only treat a missing id as an
     * orphan once it flips back to false. Particularly important on
     * backend switches, where the cache transitions from one server's
     * agents to another.
     */
    val isRefreshing: StateFlow<Boolean>
    suspend fun countAgents(): Int
    suspend fun refreshAgents()
    suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean
    fun getCachedAgent(id: String): Agent?
    fun getAgent(id: String): Flow<Agent>
    suspend fun getContextWindow(agentId: String, conversationId: String? = null): ContextWindowOverview
    suspend fun checkpointAndRestoreConfig(agentId: String, operation: suspend () -> Unit)
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
