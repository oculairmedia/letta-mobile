package com.letta.mobile.desktop.data

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

class DesktopIrohAgentRepository(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IAgentRepository {
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
    private val refreshingFlow = MutableStateFlow(false)
    private val refreshErrorFlow = MutableStateFlow<Throwable?>(null)
    private var lastRefreshMs = 0L

    override val agents: StateFlow<List<Agent>> = agentsFlow
    override val isRefreshing: StateFlow<Boolean> = refreshingFlow
    override val refreshError: StateFlow<Throwable?> = refreshErrorFlow

    override suspend fun countAgents(): Int {
        if (agentsFlow.value.isEmpty()) refreshAgents()
        return agentsFlow.value.size
    }

    override suspend fun refreshAgents() {
        val directory = directoryProvider()
            ?: throw DesktopRepositoryUnavailableException("IrohAdminRpcAgentDirectory", "refreshAgents")
        refreshingFlow.value = true
        try {
            agentsFlow.value = directory.listAgents()
            lastRefreshMs = System.currentTimeMillis()
            refreshErrorFlow.value = null
        } catch (t: Throwable) {
            refreshErrorFlow.value = t
            throw t
        } finally {
            refreshingFlow.value = false
        }
    }

    override suspend fun listAgentSummaries(): List<AgentSummary> {
        refreshAgentsIfStale(DEFAULT_REFRESH_MAX_AGE_MS)
        return agentsFlow.value.map { AgentSummary(id = it.id, name = it.name, description = it.description) }
    }

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (agentsFlow.value.isNotEmpty() && now - lastRefreshMs <= maxAgeMs) return false
        refreshAgents()
        return true
    }

    override fun getCachedAgent(id: AgentId): Agent? = agentsFlow.value.firstOrNull { it.id == id }

    override fun getAgent(id: AgentId): Flow<Agent> = flow {
        val cached = getCachedAgent(id)
        if (cached != null) {
            emit(cached)
            return@flow
        }
        val directory = directoryProvider()
            ?: throw DesktopRepositoryUnavailableException("IrohAdminRpcAgentDirectory", "getAgent")
        val agent = directory.getAgent(id.value)
            ?: throw NoSuchElementException("Agent ${id.value} not found over iroh admin_rpc")
        emit(agent)
    }

    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview {
        val directory = directoryProvider()
            ?: throw DesktopRepositoryUnavailableException("IrohAdminRpcAgentDirectory", "getContextWindow")
        return directory.getContextWindow(agentId.value, conversationId?.value)
    }

    override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) {
        operation()
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent = unsupported("createAgent")
    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = unsupported("updateAgent")
    override suspend fun deleteAgent(id: AgentId): Unit = unsupported("deleteAgent")
    override suspend fun exportAgent(id: AgentId): String = unsupported("exportAgent")
    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: ProjectId?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse = unsupported("importAgent")
    override suspend fun attachArchive(agentId: AgentId, archiveId: String): Unit = unsupported("attachArchive")
    override suspend fun detachArchive(agentId: AgentId, archiveId: String): Unit = unsupported("detachArchive")

    private fun unsupported(operation: String): Nothing =
        throw DesktopRepositoryUnavailableException("DesktopIrohAgentRepository", operation)

    private companion object {
        const val DEFAULT_REFRESH_MAX_AGE_MS = 30_000L
    }
}
