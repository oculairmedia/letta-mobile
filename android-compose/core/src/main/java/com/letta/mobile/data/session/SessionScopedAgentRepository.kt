package com.letta.mobile.data.session

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ImportedAgentsResponse
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

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.agentRepository.agents }
            .onEach { _agents.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .flatMapLatest { it.agentRepository.isRefreshing }
            .onEach { _isRefreshing.value = it }
            .launchIn(proxyScope)
    }

    private val current: IAgentRepository
        get() = sessionManager.current.agentRepository

    override suspend fun countAgents(): Int = sessionManager.withCurrentSession { it.agentRepository.countAgents() }

    override suspend fun refreshAgents() = sessionManager.withCurrentSession { it.agentRepository.refreshAgents() }

    override suspend fun clearForBackendSwitch() {
        _agents.value = emptyList()
        _isRefreshing.value = false
        sessionManager.current.agentRepository.clearForBackendSwitch()
    }

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = sessionManager.withCurrentSession { it.agentRepository.refreshAgentsIfStale(maxAgeMs) }

    override fun getCachedAgent(id: String): Agent? = current.getCachedAgent(id)

    override fun getAgent(id: String): Flow<Agent> =
        sessionManager.currentGraph.flatMapLatest { it.agentRepository.getAgent(id) }

    override suspend fun getContextWindow(agentId: String, conversationId: String?): ContextWindowOverview =
        sessionManager.withCurrentSession { it.agentRepository.getContextWindow(agentId, conversationId) }

    override suspend fun checkpointAndRestoreConfig(agentId: String, operation: suspend () -> Unit) =
        current.checkpointAndRestoreConfig(agentId, operation)

    override suspend fun createAgent(params: AgentCreateParams): Agent = sessionManager.withCurrentSession { it.agentRepository.createAgent(params) }

    override suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent = sessionManager.withCurrentSession { it.agentRepository.updateAgent(id, params) }

    override suspend fun deleteAgent(id: String) = sessionManager.withCurrentSession { it.agentRepository.deleteAgent(id) }

    override suspend fun exportAgent(id: String): String = sessionManager.withCurrentSession { it.agentRepository.exportAgent(id) }

    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: String?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse = sessionManager.withCurrentSession { it.agentRepository.importAgent(
        fileName = fileName,
        fileBytes = fileBytes,
        overrideName = overrideName,
        overrideExistingTools = overrideExistingTools,
        projectId = projectId,
        stripMessages = stripMessages,
    ) }

    override suspend fun attachArchive(agentId: String, archiveId: String) = sessionManager.withCurrentSession { it.agentRepository.attachArchive(agentId, archiveId) }

    override suspend fun detachArchive(agentId: String, archiveId: String) = sessionManager.withCurrentSession { it.agentRepository.detachArchive(agentId, archiveId) }

    fun close() { proxyScope.cancel() }
}
