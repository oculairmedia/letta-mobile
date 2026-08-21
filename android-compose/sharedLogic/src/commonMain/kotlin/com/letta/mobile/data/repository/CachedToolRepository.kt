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
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _tools.update { irohSource.listTools() }
            lastRefreshAtMillis = nowMillis()
            return
        }
        _tools.update { remote.listTools() }
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
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.attachTool(agentId.value, toolId.value)
        } else {
            remote.attachTool(agentId.value, toolId.value)
        }
        val tool = _tools.value.find { it.id == toolId }
        if (tool != null) {
            _toolsByAgent.update { current ->
                current.toMutableMap().apply {
                    val existing = get(agentId.value) ?: emptyList()
                    put(agentId.value, existing + tool)
                }
            }
        }
    }

    override suspend fun detachTool(agentId: AgentId, toolId: ToolId) {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.detachTool(agentId.value, toolId.value)
        } else {
            remote.detachTool(agentId.value, toolId.value)
        }
        _toolsByAgent.update { current ->
            current.toMutableMap().apply {
                val existing = get(agentId.value) ?: emptyList()
                put(agentId.value, existing.filter { it.id != toolId })
            }
        }
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        val irohSource = irohToolSource
        val tool = if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.createTool(params)
        } else {
            remote.upsertTool(params)
        }
        _tools.update { current ->
            val index = current.indexOfFirst { it.id == tool.id }
            if (index >= 0) current.toMutableList().apply { this[index] = tool } else current + tool
        }
        return tool
    }

    override suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool {
        val irohSource = irohToolSource
        val tool = if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.updateTool(toolId.value, params)
        } else {
            remote.updateTool(toolId.value, params)
        }
        _tools.update { current ->
            current.map { existing -> if (existing.id == tool.id) tool else existing }
        }
        _toolsByAgent.update { current ->
            current.mapValues { (_, tools) ->
                tools.map { existing -> if (existing.id == tool.id) tool else existing }
            }
        }
        return tool
    }

    override suspend fun deleteTool(toolId: ToolId) {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.deleteTool(toolId.value)
        } else {
            remote.deleteTool(toolId.value)
        }
        _tools.update { current -> current.filterNot { it.id == toolId } }
        _toolsByAgent.update { current ->
            current.mapValues { (_, tools) -> tools.filterNot { it.id == toolId } }
        }
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
