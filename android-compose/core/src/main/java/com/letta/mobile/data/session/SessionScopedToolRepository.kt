package com.letta.mobile.data.session

import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.IToolRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedToolRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedToolRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IToolRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedToolRepositoryScope(),
    )

    private val _tools = MutableStateFlow(sessionManager.current.toolRepository.getTools().value)
    private val cacheLock = Any()
    private val agentToolFlows = mutableMapOf<String, MutableStateFlow<List<Tool>>>()
    private val agentToolJobs = mutableMapOf<String, Job>()

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.toolRepository.getTools() }
            .onEach { _tools.value = it }
            .launchIn(proxyScope)
    }

    private val current: IToolRepository
        get() = sessionManager.current.toolRepository

    override fun getTools(): StateFlow<List<Tool>> = _tools.asStateFlow()

    override fun getAgentTools(agentId: String): Flow<List<Tool>> = synchronized(cacheLock) {
        val flow = agentToolFlows.getOrPut(agentId) {
            MutableStateFlow(emptyList())
        }
        agentToolJobs.getOrPut(agentId) {
            sessionManager.currentGraph
                .flatMapLatest { it.toolRepository.getAgentTools(agentId) }
                .onEach { flow.value = it }
                .launchIn(proxyScope)
        }
        flow.asStateFlow()
    }

    override suspend fun countTools(): Int = current.countTools()

    override suspend fun refreshTools() = current.refreshTools()
    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean = current.refreshToolsIfStale(maxAgeMs)
    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> = current.fetchToolsPage(limit, offset)
    override suspend fun attachTool(agentId: String, toolId: String) = current.attachTool(agentId, toolId)
    override suspend fun detachTool(agentId: String, toolId: String) = current.detachTool(agentId, toolId)
    override suspend fun upsertTool(params: ToolCreateParams): Tool = current.upsertTool(params)
    override suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool = current.updateTool(toolId, params)
    override suspend fun deleteTool(toolId: String) = current.deleteTool(toolId)
}
