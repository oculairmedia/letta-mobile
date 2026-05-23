package com.letta.mobile.data.session

import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.api.IMcpServerRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedMcpServerRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedMcpServerRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IMcpServerRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedMcpServerRepositoryScope(),
    )

    private val _servers = MutableStateFlow(sessionManager.current.mcpServerRepository.servers.value)
    override val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    private val cacheLock = Any()
    private val serverToolFlows = mutableMapOf<String, MutableStateFlow<List<Tool>>>()
    private val serverToolJobs = mutableMapOf<String, Job>()

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.mcpServerRepository.servers }
            .onEach { _servers.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .drop(1)
            .onEach {
                synchronized(cacheLock) {
                    serverToolJobs.values.forEach { it.cancel() }
                    serverToolJobs.clear()
                    serverToolFlows.values.forEach { it.value = emptyList() }
                    serverToolFlows.clear()
                }
            }
            .launchIn(proxyScope)
    }

    private val current: IMcpServerRepository
        get() = sessionManager.current.mcpServerRepository

    override fun getServers(): Flow<List<McpServer>> = servers

    override fun getServerTools(serverId: String): Flow<List<Tool>> = synchronized(cacheLock) {
        val flow = serverToolFlows.getOrPut(serverId) {
            MutableStateFlow(emptyList())
        }
        serverToolJobs.getOrPut(serverId) {
            sessionManager.currentGraph
                .flatMapLatest { it.mcpServerRepository.getServerTools(serverId) }
                .onEach { flow.value = it }
                .launchIn(proxyScope)
        }
        flow.asStateFlow()
    }

    override suspend fun refreshServers() = sessionManager.withCurrentSession { it.mcpServerRepository.refreshServers() }
    override suspend fun refreshServerTools(serverId: String) = sessionManager.withCurrentSession { it.mcpServerRepository.refreshServerTools(serverId) }
    override suspend fun resyncServerTools(serverId: String): McpServerResyncResult =
        sessionManager.withCurrentSession { it.mcpServerRepository.resyncServerTools(serverId) }

    override suspend fun runServerTool(
        serverId: String,
        toolId: String,
        params: McpToolExecuteParams,
    ): McpToolExecutionResult = sessionManager.withCurrentSession { it.mcpServerRepository.runServerTool(serverId, toolId, params) }

    override suspend fun fetchAllMcpTools(): List<Tool> = sessionManager.withCurrentSession { it.mcpServerRepository.fetchAllMcpTools() }
    override suspend fun createServer(params: McpServerCreateParams): McpServer = sessionManager.withCurrentSession { it.mcpServerRepository.createServer(params) }
    override suspend fun updateServer(id: String, params: McpServerUpdateParams): McpServer = sessionManager.withCurrentSession { it.mcpServerRepository.updateServer(id, params) }
    override suspend fun deleteServer(id: String) = sessionManager.withCurrentSession { it.mcpServerRepository.deleteServer(id) }

    fun close() { proxyScope.cancel() }
}
