package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.letta.mobile.data.repository.api.IToolRepository
import javax.inject.Inject

open class ToolRepository @Inject constructor(
    private val toolApi: ToolApi,
    private val irohToolSource: IrohAdminRpcToolSource? = null,
) : IToolRepository {
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())
    private val _toolsByAgent = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())
    private val refreshMutex = Mutex()
    private var lastRefreshAtMillis: Long = 0L

    override fun getTools(): StateFlow<List<Tool>> = _tools.asStateFlow()

    override fun getAgentTools(agentId: AgentId): Flow<List<Tool>> {
        return _toolsByAgent.map { it[agentId.value] ?: emptyList() }
    }

    override suspend fun countTools(): Int = toolApi.countTools()

    override suspend fun refreshTools() = refreshMutex.withLock {
        refreshToolsLocked()
    }

    private suspend fun refreshToolsLocked() {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _tools.update { irohSource.listTools() }
            lastRefreshAtMillis = System.currentTimeMillis()
            return
        }
        _tools.update { toolApi.listTools() }
        lastRefreshAtMillis = System.currentTimeMillis()
    }

    fun hasFreshTools(maxAgeMs: Long): Boolean {
        return _tools.value.isNotEmpty() && System.currentTimeMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshTools(maxAgeMs)) return@withLock false
        refreshToolsLocked()
        true
    }

    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> {
        return toolApi.listTools(limit = limit, offset = offset)
    }

    fun refreshAgentTools(agentId: String, tools: List<Tool>) {
        _toolsByAgent.update { current -> current.toMutableMap().apply {
                    put(agentId, tools)
                } }
    }

    override suspend fun attachTool(agentId: AgentId, toolId: ToolId) {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.attachTool(agentId.value, toolId.value)
        } else {
            toolApi.attachTool(agentId.value, toolId.value)
        }
        val tool = _tools.value.find { it.id == toolId }
        if (tool != null) {
            _toolsByAgent.update { current -> current.toMutableMap().apply {
                        val existing = get(agentId.value) ?: emptyList()
                        put(agentId.value, existing + tool)
                    } }
        }
    }

    override suspend fun detachTool(agentId: AgentId, toolId: ToolId) {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.detachTool(agentId.value, toolId.value)
        } else {
            toolApi.detachTool(agentId.value, toolId.value)
        }
        _toolsByAgent.update { current -> current.toMutableMap().apply {
                    val existing = get(agentId.value) ?: emptyList()
                    put(agentId.value, existing.filter { it.id != toolId })
                } }
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        val irohSource = irohToolSource
        val tool = if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.createTool(params)
        } else {
            toolApi.upsertTool(params)
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
            toolApi.updateTool(toolId.value, params)
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
            toolApi.deleteTool(toolId.value)
        }
        _tools.update { current -> current.filterNot { it.id == toolId } }
        _toolsByAgent.update { current ->
            current.mapValues { (_, tools) -> tools.filterNot { it.id == toolId } }
        }
    }
}
