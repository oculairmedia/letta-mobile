package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ProjectId
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
    /**
     * Exposes the last encountered refresh/sync exception (e.g. connection timeout,
     * invalid credentials, offline state) so that UI consumers can surface recoverable
     * errors. Set to null when a refresh succeeds.
     */
    val refreshError: StateFlow<Throwable?>
    suspend fun countAgents(): Int
    suspend fun refreshAgents()
    suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean
    fun getCachedAgent(id: AgentId): Agent?
    fun getCachedAgent(id: String): Agent? = getCachedAgent(AgentId(id))
    fun getAgent(id: AgentId): Flow<Agent>
    fun getAgent(id: String): Flow<Agent> = getAgent(AgentId(id))
    suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId? = null): ContextWindowOverview
    suspend fun getContextWindow(agentId: String, conversationId: String? = null): ContextWindowOverview =
        getContextWindow(AgentId(agentId), conversationId?.let(::ConversationId))
    suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit)
    suspend fun checkpointAndRestoreConfig(agentId: String, operation: suspend () -> Unit) =
        checkpointAndRestoreConfig(AgentId(agentId), operation)
    suspend fun createAgent(params: AgentCreateParams): Agent
    suspend fun createLocalAgent(params: AgentCreateParams): Agent =
        throw UnsupportedOperationException("Local agent creation is not supported by this repository")
    suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent
    suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent = updateAgent(AgentId(id), params)
    suspend fun deleteAgent(id: AgentId)
    suspend fun deleteAgent(id: String) = deleteAgent(AgentId(id))
    suspend fun exportAgent(id: AgentId): String
    suspend fun exportAgent(id: String): String = exportAgent(AgentId(id))
    suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String? = null,
        overrideExistingTools: Boolean? = null,
        projectId: ProjectId? = null,
        stripMessages: Boolean? = null,
    ): ImportedAgentsResponse
    suspend fun attachArchive(agentId: AgentId, archiveId: String)
    suspend fun attachArchive(agentId: String, archiveId: String) = attachArchive(AgentId(agentId), archiveId)
    suspend fun detachArchive(agentId: AgentId, archiveId: String)
    suspend fun detachArchive(agentId: String, archiveId: String) = detachArchive(AgentId(agentId), archiveId)
}
