package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer

class AppServerAgentRepository(
    private val transport: AppServerLocalRepositoryTransport,
) : IAgentRepository {
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
    private val refreshingFlow = MutableStateFlow(false)
    private val refreshErrorFlow = MutableStateFlow<Throwable?>(null)
    private var lastRefreshMs = 0L

    override val agents: StateFlow<List<Agent>> = agentsFlow
    override val isRefreshing: StateFlow<Boolean> = refreshingFlow
    override val refreshError: StateFlow<Throwable?> = refreshErrorFlow

    override suspend fun countAgents(): Int {
        refreshAgentsIfStale(DEFAULT_REFRESH_MAX_AGE_MS)
        return agentsFlow.value.size
    }

    override suspend fun refreshAgents() {
        refreshingFlow.value = true
        try {
            agentsFlow.value = AppServerProtocol.json.decodeFromJsonElement(
                ListSerializer(Agent.serializer()),
                transport.listAgents(),
            )
            lastRefreshMs = Clock.System.now().toEpochMilliseconds()
            refreshErrorFlow.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            refreshErrorFlow.value = failure
            throw failure
        } finally {
            refreshingFlow.value = false
        }
    }

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        if (agentsFlow.value.isNotEmpty() && now - lastRefreshMs <= maxAgeMs) return false
        refreshAgents()
        return true
    }

    override suspend fun listAgentSummaries(): List<AgentSummary> {
        refreshAgentsIfStale(DEFAULT_REFRESH_MAX_AGE_MS)
        return agentsFlow.value.map { AgentSummary(it.id, it.name, it.description) }
    }

    override fun getCachedAgent(id: AgentId): Agent? = agentsFlow.value.firstOrNull { it.id == id }

    override fun getAgent(id: AgentId): Flow<Agent> = flow {
        refreshAgentsIfStale(0L)
        emit(getCachedAgent(id) ?: throw NoSuchElementException("Local agent ${id.value} was not found"))
    }

    override suspend fun getContextWindow(
        agentId: AgentId,
        conversationId: ConversationId?,
    ): ContextWindowOverview {
        val context = transport.getContext(agentId.value, conversationId?.value)
            ?: throw NoSuchElementException("Local context for agent ${agentId.value} was not found")
        return AppServerProtocol.json.decodeFromJsonElement(ContextWindowOverview.serializer(), context)
    }

    override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) = operation()
    override suspend fun createAgent(params: AgentCreateParams): Agent = unsupported("createAgent")
    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = unsupported("updateAgent")
    override suspend fun deleteAgent(id: AgentId): Unit = unsupported("deleteAgent")
    override suspend fun exportAgent(id: AgentId): String = unsupported("exportAgent")
    override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = unsupported("importAgent")
    override suspend fun attachArchive(agentId: AgentId, archiveId: String): Unit = unsupported("attachArchive")
    override suspend fun detachArchive(agentId: AgentId, archiveId: String): Unit = unsupported("detachArchive")

    private fun unsupported(operation: String): Nothing =
        throw UnsupportedOperationException("Bundled local backend does not support $operation yet")

    private companion object {
        const val DEFAULT_REFRESH_MAX_AGE_MS = 30_000L
    }
}
