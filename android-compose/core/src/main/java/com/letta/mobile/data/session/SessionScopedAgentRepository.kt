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
    proxyScope: CoroutineScope,
) : IAgentRepository {
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

    override suspend fun countAgents(): Int = current.countAgents()

    override suspend fun refreshAgents() = current.refreshAgents()

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = current.refreshAgentsIfStale(maxAgeMs)

    override fun getCachedAgent(id: String): Agent? = current.getCachedAgent(id)

    override fun getAgent(id: String): Flow<Agent> =
        sessionManager.currentGraph.flatMapLatest { it.agentRepository.getAgent(id) }

    override suspend fun getContextWindow(agentId: String, conversationId: String?): ContextWindowOverview =
        current.getContextWindow(agentId, conversationId)

    override suspend fun checkpointAndRestoreConfig(agentId: String, operation: suspend () -> Unit) =
        current.checkpointAndRestoreConfig(agentId, operation)

    override suspend fun createAgent(params: AgentCreateParams): Agent = current.createAgent(params)

    override suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent = current.updateAgent(id, params)

    override suspend fun deleteAgent(id: String) = current.deleteAgent(id)

    override suspend fun exportAgent(id: String): String = current.exportAgent(id)

    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: String?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse = current.importAgent(
        fileName = fileName,
        fileBytes = fileBytes,
        overrideName = overrideName,
        overrideExistingTools = overrideExistingTools,
        projectId = projectId,
        stripMessages = stripMessages,
    )

    override suspend fun attachArchive(agentId: String, archiveId: String) = current.attachArchive(agentId, archiveId)

    override suspend fun detachArchive(agentId: String, archiveId: String) = current.detachArchive(agentId, archiveId)
}
