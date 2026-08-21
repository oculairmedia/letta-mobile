package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.ToolIrohSource
import com.letta.mobile.data.repository.api.ToolRemoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Phase 5c: platform-neutral cached tool repository. Android supplies HTTP
 * [ToolRemoteSource] and optional [ToolIrohSource]; no Room cache (in-memory only).
 *
 * Desktop continues to use [com.letta.mobile.data.repository.iroh.IrohToolRepository]
 * / HTTP admin for its session graph; unifying those hosts is a follow-up.
 */
open class CachedToolRepository(
    private val remote: ToolRemoteSource,
    private val irohToolSource: ToolIrohSource? = null,
) : IToolRepository {
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())
    private val _toolsByAgent = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())
    private val refreshMutex = Mutex()
    private var lastRefreshAtMillis: Long = 0L

    override fun getTools(): StateFlow<List<Tool>> = _tools.asStateFlow()

    override fun getAgentTools(agentId: AgentId): Flow<List<Tool>> {
        return _toolsByAgent.map { it[agentId.value] ?: emptyList() }
    }

    override suspend fun countTools(): Int = remote.countTools()

    override suspend fun refreshTools() = refreshMutex.withLock {
        refreshToolsLocked()
    }

    private suspend fun refreshToolsLocked() {
        _tools.update {
            activeIrohSource()?.listTools() ?: remote.listTools()
        }
        lastRefreshAtMillis = nowMillis()
    }

    fun hasFreshTools(maxAgeMs: Long): Boolean {
        return _tools.value.isNotEmpty() && nowMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshTools(maxAgeMs)) return@withLock false
        refreshToolsLocked()
        true
    }

    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> {
        return remote.listTools(limit = limit, offset = offset)
    }

    override suspend fun attachTool(agentId: AgentId, toolId: ToolId) {
        viaActiveSource(
            iroh = { it.attachTool(agentId.value, toolId.value) },
            remote = { remote.attachTool(agentId.value, toolId.value) },
        )
        _tools.value.find { it.id == toolId }?.let { tool ->
            addToolToAgentCache(agentId, tool)
        }
    }

    override suspend fun detachTool(agentId: AgentId, toolId: ToolId) {
        viaActiveSource(
            iroh = { it.detachTool(agentId.value, toolId.value) },
            remote = { remote.detachTool(agentId.value, toolId.value) },
        )
        removeToolFromAgentCache(agentId, toolId)
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        val tool = viaActiveSource(
            iroh = { it.createTool(params) },
            remote = { remote.upsertTool(params) },
        )
        upsertToolInGlobalList(tool)
        return tool
    }

    override suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool {
        val tool = viaActiveSource(
            iroh = { it.updateTool(toolId.value, params) },
            remote = { remote.updateTool(toolId.value, params) },
        )
        replaceToolInGlobalList(tool)
        replaceToolInAgentLists(tool)
        return tool
    }

    override suspend fun deleteTool(toolId: ToolId) {
        viaActiveSource(
            iroh = { it.deleteTool(toolId.value) },
            remote = { remote.deleteTool(toolId.value) },
        )
        removeToolFromAllCaches(toolId)
    }

    private fun activeIrohSource(): ToolIrohSource? =
        irohToolSource?.takeIf { it.shouldUseIroh() }

    private suspend fun <T> viaActiveSource(
        iroh: suspend (ToolIrohSource) -> T,
        remote: suspend () -> T,
    ): T {
        val source = activeIrohSource()
        return if (source != null) iroh(source) else remote()
    }

    private fun addToolToAgentCache(agentId: AgentId, tool: Tool) {
        _toolsByAgent.update { current ->
            current.toMutableMap().apply {
                val existing = get(agentId.value) ?: emptyList()
                put(agentId.value, existing + tool)
            }
        }
    }

    private fun removeToolFromAgentCache(agentId: AgentId, toolId: ToolId) {
        _toolsByAgent.update { current ->
            current.toMutableMap().apply {
                val existing = get(agentId.value) ?: emptyList()
                put(agentId.value, existing.filter { it.id != toolId })
            }
        }
    }

    private fun upsertToolInGlobalList(tool: Tool) {
        _tools.update { current ->
            val index = current.indexOfFirst { it.id == tool.id }
            if (index >= 0) current.toMutableList().apply { this[index] = tool } else current + tool
        }
    }

    private fun replaceToolInGlobalList(tool: Tool) {
        _tools.update { current ->
            current.map { existing -> if (existing.id == tool.id) tool else existing }
        }
    }

    private fun replaceToolInAgentLists(tool: Tool) {
        _toolsByAgent.update { current ->
            current.mapValues { (_, tools) ->
                tools.map { existing -> if (existing.id == tool.id) tool else existing }
            }
        }
    }

    private fun removeToolFromAllCaches(toolId: ToolId) {
        _tools.update { current -> current.filterNot { it.id == toolId } }
        _toolsByAgent.update { current ->
            current.mapValues { (_, tools) -> tools.filterNot { it.id == toolId } }
        }
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
