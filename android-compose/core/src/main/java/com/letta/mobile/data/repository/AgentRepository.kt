package com.letta.mobile.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.paging.AgentPagingSource
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val agentApi: AgentApi,
    private val agentDao: AgentDao,
) : IAgentRepository {
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var lastRefreshAtMillis: Long = 0L

    init {
        repositoryScope.launch {
            try {
                val cached = agentDao.getAllOnce().map { it.toAgent() }
                if (cached.isNotEmpty()) {
                    _agents.value = cached
                }
            } catch (e: Exception) {
                Log.w("AgentRepository", "Failed to load cached agents", e)
            }
        }
    }

    fun getAgentsPaged(tags: List<String>? = null): Flow<PagingData<Agent>> {
        return Pager(
            config = PagingConfig(
                pageSize = AgentPagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = AgentPagingSource.PAGE_SIZE * 2
            ),
            pagingSourceFactory = { AgentPagingSource(agentApi, tags) }
        ).flow
    }

    override suspend fun countAgents(): Int = agentApi.countAgents()

    override suspend fun refreshAgents() = refreshMutex.withLock {
        _isRefreshing.value = true
        try {
            refreshAgentsLocked()
        } finally {
            _isRefreshing.value = false
        }
    }

    private suspend fun refreshAgentsLocked() {
        val fresh = fetchAgentsForCache { partial ->
            _agents.update { partial }
        }
        _agents.update { fresh }
        lastRefreshAtMillis = System.currentTimeMillis()
        try {
            val entities = fresh.map { AgentEntity.fromAgent(it) }
            agentDao.insertAll(entities)
            if (fresh.isEmpty()) {
                agentDao.deleteAll()
            } else {
                agentDao.deleteExcept(fresh.map { it.id.value })
            }
        } catch (e: Exception) {
            Log.w("AgentRepository", "Failed to cache agents to Room", e)
        }
    }

    private suspend fun fetchAgentsForCache(
        onProgress: suspend (List<Agent>) -> Unit = {},
    ): List<Agent> {
        val merged = mutableListOf<Agent>()
        var offset = AgentPagingSource.INITIAL_OFFSET
        while (true) {
            val page = agentApi.listAgents(limit = CACHE_REFRESH_PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break

            val existingIds = merged.asSequence().map { it.id }.toSet()
            val newAgents = page.filter { it.id !in existingIds }
            if (newAgents.isEmpty()) {
                Log.w("AgentRepository", "Agent list offset pagination made no progress; falling back to count-sized fetch")
                return fetchAgentsWithoutOffsetFallback(onProgress)
            }

            merged += newAgents
            onProgress(merged.toList())
            if (page.size < CACHE_REFRESH_PAGE_SIZE) break
            offset += page.size
        }
        return merged
    }

    private suspend fun fetchAgentsWithoutOffsetFallback(
        onProgress: suspend (List<Agent>) -> Unit,
    ): List<Agent> {
        val fallbackLimit = runCatching { agentApi.countAgents() }
            .getOrDefault(FALLBACK_FULL_FETCH_LIMIT)
            .coerceAtLeast(CACHE_REFRESH_PAGE_SIZE)
        val fullList = agentApi.listAgents(limit = fallbackLimit, offset = null)
            .distinctBy { it.id }
        if (fullList.isNotEmpty()) {
            onProgress(fullList)
        }
        return fullList
    }

    override fun getCachedAgent(id: String): Agent? = _agents.value.find { it.id == AgentId(id) }

    fun hasFreshAgents(maxAgeMs: Long): Boolean {
        return _agents.value.isNotEmpty() && System.currentTimeMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshAgents(maxAgeMs)) return@withLock false
        _isRefreshing.value = true
        try {
            refreshAgentsLocked()
        } finally {
            _isRefreshing.value = false
        }
        true
    }

    override fun getAgent(id: String): Flow<Agent> = flow {
        val cached = _agents.value.find { it.id == AgentId(id) }
        if (cached != null) {
            emit(cached)
        }
        val fresh = agentApi.getAgent(id)
        emit(fresh)
        updateAgentInCache(fresh)
    }

    suspend fun getContextWindow(agentId: String, conversationId: String? = null) =
        agentApi.getContextWindow(agentId, conversationId)

    fun getAgentPolling(id: String): Flow<Agent> = flow {
        while (true) {
            val agent = agentApi.getAgent(id)
            emit(agent)
            updateAgentInCache(agent)
            delay(3000)
        }
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        val agent = agentApi.createAgent(params)
        refreshAgents()
        return agent
    }

    override suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent {
        val agent = agentApi.updateAgent(id, params)
        refreshAgents()
        return agent
    }

    override suspend fun deleteAgent(id: String) {
        agentApi.deleteAgent(id)
        _agents.update { current -> current.filterNot { it.id == AgentId(id) } }
        try {
            agentDao.deleteExcept(_agents.value.map { it.id.value })
        } catch (e: Exception) {
            Log.w("AgentRepository", "Failed to update cached agents after delete", e)
        }
    }

    override suspend fun exportAgent(id: String): String {
        return agentApi.exportAgent(id)
    }

    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: String?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse {
        val response = agentApi.importAgent(
            fileName = fileName,
            fileBytes = fileBytes,
            overrideName = overrideName,
            overrideExistingTools = overrideExistingTools,
            projectId = projectId,
            stripMessages = stripMessages,
        )
        refreshAgents()
        return response
    }

    override suspend fun attachArchive(agentId: String, archiveId: String) {
        agentApi.attachArchive(agentId, archiveId)
        refreshAgents()
    }

    override suspend fun detachArchive(agentId: String, archiveId: String) {
        agentApi.detachArchive(agentId, archiveId)
        refreshAgents()
    }

    private fun updateAgentInCache(agent: Agent) {
        _agents.update { current ->
            val index = current.indexOfFirst { it.id == agent.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = agent }
            } else {
                current + agent
            }
        }
    }

    override suspend fun checkpointAndRestoreConfig(
        agentId: String,
        operation: suspend () -> Unit
    ) {
        val agent = agentApi.getAgent(agentId)
        val checkpoint = com.letta.mobile.data.model.AgentConfigCheckpoint.from(agent)
        
        try {
            operation()
        } finally {
            val updatedAgent = agentApi.getAgent(agentId)
            if (!checkpoint.matches(updatedAgent)) {
                Log.w("AgentRepository", "Agent config drift detected after operation, restoring checkpoint")
                Telemetry.event(
                    "AgentRepository",
                    "configDriftDetected",
                    "agentId" to agentId,
                    "originalModel" to checkpoint.model,
                    "driftedModel" to updatedAgent.model,
                    level = Telemetry.Level.WARN
                )
                updateAgent(agentId, checkpoint.toUpdateParams())
                Telemetry.event(
                    "AgentRepository",
                    "configRestored",
                    "agentId" to agentId,
                    "restoredModel" to checkpoint.model
                )
            }
        }
    }
    private companion object {
        const val CACHE_REFRESH_PAGE_SIZE = 50
        const val FALLBACK_FULL_FETCH_LIMIT = 5_000
    }
}
