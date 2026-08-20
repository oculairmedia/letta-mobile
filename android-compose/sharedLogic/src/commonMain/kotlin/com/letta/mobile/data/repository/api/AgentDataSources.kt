package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse

/**
 * Remote HTTP (or equivalent) agent admin surface used by [com.letta.mobile.data.repository.CachedAgentRepository].
 * Platform modules supply Ktor/[AgentApi] bindings; Iroh traffic goes through [AgentIrohSource].
 */
interface AgentRemoteSource {
    suspend fun listAgents(limit: Int? = null, offset: Int? = null, tags: List<String>? = null): List<Agent>
    suspend fun listAgentsSlim(limit: Int? = null, offset: Int? = null, tags: List<String>? = null): List<AgentSummary>
    suspend fun getAgent(agentId: AgentId): Agent
    suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId? = null): ContextWindowOverview
    suspend fun countAgents(): Int
    suspend fun createAgent(params: AgentCreateParams): Agent
    suspend fun updateAgent(agentId: AgentId, params: AgentUpdateParams): Agent
    suspend fun deleteAgent(agentId: AgentId)
    suspend fun exportAgent(agentId: AgentId): String
    suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse
    suspend fun attachArchive(agentId: AgentId, archiveId: String)
    suspend fun detachArchive(agentId: AgentId, archiveId: String)
}

/**
 * Optional durable agent cache (Room on Android). Domain [Agent] values — platforms
 * own entity mapping.
 */
interface AgentLocalCache {
    suspend fun getAllOnce(): List<Agent>
    suspend fun insertAll(agents: List<Agent>)
    suspend fun upsert(agent: Agent)
    suspend fun deleteExcept(keepIds: List<String>)
    suspend fun deleteById(id: String)
    suspend fun deleteAll()
}

/**
 * Iroh admin_rpc agent surface. Implemented by [com.letta.mobile.data.repository.IrohAdminRpcAgentSource]
 * (or Phase 4c sharedLogic equivalent).
 */
interface AgentIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun countAgents(): Int
    suspend fun listAgents(): List<Agent>
    suspend fun getAgent(id: AgentId): Agent
    suspend fun createAgent(paramsJson: String): Agent
    suspend fun updateAgent(id: AgentId, paramsJson: String): Agent
    suspend fun deleteAgent(id: AgentId)
    suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview
}
