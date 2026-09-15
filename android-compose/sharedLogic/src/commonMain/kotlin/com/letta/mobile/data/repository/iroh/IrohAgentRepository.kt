package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.observeAgentUpdates
import com.letta.mobile.data.repository.observeReconnectRefresh
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

class IrohAgentRepository(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
    /**
     * When given with [scope], the roster follows Meridian's `agent_updated` pushes and refreshes
     * after a reconnect, as Android's CachedAgentRepository does, instead of only on demand.
     */
    transport: IChannelTransport? = null,
    scope: CoroutineScope? = null,
) : IAgentRepository {
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
    private val refreshingFlow = MutableStateFlow(false)
    private val refreshErrorFlow = MutableStateFlow<Throwable?>(null)
    private var lastRefreshMs = 0L

    init {
        if (transport != null && scope != null) {
            scope.launch {
                observeAgentUpdates(
                    transport = transport,
                    onDeleted = { agentId -> agentsFlow.update { current -> current.filterNot { it.id == agentId } } },
                    onChanged = { agentId -> refetch(agentId) },
                )
            }
            scope.launch {
                observeReconnectRefresh(transport) { runCatchingCancellable { refreshAgents() } }
            }
        }
    }

    /** One agent, fresh from Meridian; a failed fetch keeps the cached copy (the next refresh reconciles). */
    private suspend fun refetch(agentId: AgentId) {
        val directory = directoryProvider() ?: return
        runCatchingCancellable { directory.getAgent(agentId) }
            .onSuccess { agent -> agent?.let(::updateAgentInCache) }
            .onFailure { e ->
                Telemetry.event("IrohAgentRepository", "agent_updated.refetch_failed", "agentId" to agentId.value, "error" to (e.message ?: e::class.simpleName), level = Telemetry.Level.WARN)
            }
    }

    override val agents: StateFlow<List<Agent>> = agentsFlow
    override val isRefreshing: StateFlow<Boolean> = refreshingFlow
    override val refreshError: StateFlow<Throwable?> = refreshErrorFlow

    override suspend fun countAgents(): Int = directory().countAgents()

    override suspend fun refreshAgents() {
        refreshingFlow.value = true
        try {
            val newAgents = directory().listAgents()
            val currentAgents = agentsFlow.value

            // Diff against the cached list to avoid unnecessary StateFlow emissions.
            // StateFlow notifies collectors on every assignment, which triggers
            // Compose recomposition. By comparing by agent.id and reusing unchanged
            // instances, we preserve UI state (scroll position, expanded items,
            // selection) when the agent list hasn't actually changed.
            val currentById = currentAgents.associateBy { it.id }
            val idsChanged = currentAgents.map { it.id }.toSet() != newAgents.map { it.id }.toSet()
            val dataChanged = !idsChanged && newAgents.any { newAgent ->
                currentById[newAgent.id]?.let { it != newAgent } ?: false
            }

            if (idsChanged || dataChanged) {
                agentsFlow.value = newAgents.map { newAgent ->
                    currentById[newAgent.id]?.takeIf { it == newAgent } ?: newAgent
                }
            }

            lastRefreshMs = Clock.System.now().toEpochMilliseconds()
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
        val now = Clock.System.now().toEpochMilliseconds()
        if (agentsFlow.value.isNotEmpty() && now - lastRefreshMs <= maxAgeMs) return false
        refreshAgents()
        return true
    }

    override fun getCachedAgent(id: AgentId): Agent? = agentsFlow.value.firstOrNull { it.id == id }

    override fun getAgent(id: AgentId): Flow<Agent> = flow {
        val agent = try {
            directory().getAgent(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            getCachedAgent(id)?.let { cached ->
                emit(cached)
                return@flow
            }
            throw t
        } ?: run {
            getCachedAgent(id)?.let { cached ->
                emit(cached)
                return@flow
            }
            throw NoSuchElementException("Agent ${id.value} not found over iroh admin_rpc")
        }
        updateAgentInCache(agent)
        emit(agent)
    }

    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview =
        directory().getContextWindow(agentId, conversationId)

    override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) {
        operation()
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent = unsupported("createAgent")

    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent {
        val agent = directory().updateAgent(id, params)
        updateAgentInCache(agent)
        return agent
    }

    override suspend fun deleteAgent(id: AgentId): Unit = unsupported("deleteAgent")

    override suspend fun exportAgent(id: AgentId): String = unsupported("exportAgent")

    override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse =
        unsupported("importAgent")

    override suspend fun attachArchive(agentId: AgentId, archiveId: String): Unit = unsupported("attachArchive")

    override suspend fun detachArchive(agentId: AgentId, archiveId: String): Unit = unsupported("detachArchive")

    private fun updateAgentInCache(agent: Agent) {
        agentsFlow.update { current ->
            val index = current.indexOfFirst { it.id == agent.id }
            if (index >= 0) current.toMutableList().apply { this[index] = agent } else current + agent
        }
    }

    private fun directory(): IrohAdminRpcAgentDirectory =
        directoryProvider() ?: error("Iroh admin RPC directory is unavailable for agents")

    private fun unsupported(operation: String): Nothing =
        throw UnsupportedOperationException("IrohAgentRepository does not support $operation over iroh admin_rpc")

    private companion object {
        const val DEFAULT_REFRESH_MAX_AGE_MS = 30_000L
    }
}
