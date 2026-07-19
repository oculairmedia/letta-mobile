package com.letta.mobile.data.session

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.repository.api.IAgentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedAgentRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedAgentRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IAgentRepository, BackendScopedCache {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedAgentRepositoryScope(),
    )

    private val _agents = MutableStateFlow(sessionManager.current.agentRepository.agents.value)
    override val agents: StateFlow<List<Agent>> = _agents
    private val _isRefreshing = MutableStateFlow(sessionManager.current.agentRepository.isRefreshing.value)
    override val isRefreshing: StateFlow<Boolean> = _isRefreshing
    private val _refreshError = MutableStateFlow(sessionManager.current.agentRepository.refreshError.value)
    override val refreshError: StateFlow<Throwable?> = _refreshError

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.agentRepository.agents }
            .onEach { _agents.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .flatMapLatest { it.agentRepository.isRefreshing }
            .onEach { _isRefreshing.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .flatMapLatest { it.agentRepository.refreshError }
            .onEach { _refreshError.value = it }
            .launchIn(proxyScope)
    }

    private val current: IAgentRepository
        get() = sessionManager.current.agentRepository

    override suspend fun countAgents(): Int = sessionManager.withCurrentSession { it.agentRepository.countAgents() }

    override suspend fun refreshAgents() = sessionManager.withCurrentSession {
        it.agentRepository.refreshAgents()
        syncProxyState(it)
    }

    override suspend fun clearForBackendSwitch() {
        _agents.value = emptyList()
        _isRefreshing.value = false
        _refreshError.value = null
        sessionManager.current.agentRepository.clearForBackendSwitch()
    }

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = sessionManager.withCurrentSession {
        val refreshed = it.agentRepository.refreshAgentsIfStale(maxAgeMs)
        syncProxyState(it)
        refreshed
    }

    // Same race as SessionScopedAllConversationsRepository: the proxy
    // StateFlows are fed asynchronously, so refresh-then-read callers saw the
    // pre-refresh snapshot. Copy state synchronously on refresh.
    private fun syncProxyState(graph: SessionGraph) {
        _agents.value = graph.agentRepository.agents.value
    }

    override fun getCachedAgent(id: AgentId): Agent? = current.getCachedAgent(id)

    override fun getAgent(id: AgentId): Flow<Agent> =
        sessionManager.currentGraph.flatMapLatest { it.agentRepository.getAgent(id) }

    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview =
        sessionManager.withCurrentSession { it.agentRepository.getContextWindow(agentId, conversationId) }

    override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) =
        current.checkpointAndRestoreConfig(agentId, operation)

    override suspend fun createAgent(params: AgentCreateParams): Agent = sessionManager.withCurrentSession { it.agentRepository.createAgent(params) }

    override suspend fun createLocalAgent(params: AgentCreateParams): Agent = sessionManager.withCurrentSession { it.agentRepository.createLocalAgent(params) }

    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = sessionManager.withCurrentSession { it.agentRepository.updateAgent(id, params) }

    override suspend fun deleteAgent(id: AgentId) = sessionManager.withCurrentSession { it.agentRepository.deleteAgent(id) }

    override suspend fun exportAgent(id: AgentId): String = sessionManager.withCurrentSession { it.agentRepository.exportAgent(id) }

    override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse =
        sessionManager.withCurrentSession { it.agentRepository.importAgent(params) }

    override suspend fun attachArchive(agentId: AgentId, archiveId: String) = sessionManager.withCurrentSession { it.agentRepository.attachArchive(agentId, archiveId) }

    override suspend fun detachArchive(agentId: AgentId, archiveId: String) = sessionManager.withCurrentSession { it.agentRepository.detachArchive(agentId, archiveId) }

    fun close() { proxyScope.cancel() }
}
