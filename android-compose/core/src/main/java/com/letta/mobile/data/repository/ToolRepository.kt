package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.letta.mobile.data.repository.api.IToolRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRepository @Inject constructor(
    private val toolApi: ToolApi,
) : IToolRepository {
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())
    private val _toolsByAgent = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())
    private var lastRefreshAtMillis: Long = 0L

    override fun getTools(): Flow<List<Tool>> = _tools

    override fun getAgentTools(agentId: String): Flow<List<Tool>> {
        return _toolsByAgent.map { it[agentId] ?: emptyList() }
    }

    override suspend fun countTools(): Int = toolApi.countTools()

    override suspend fun refreshTools() {
        _tools.update { toolApi.listTools() }
        lastRefreshAtMillis = System.currentTimeMillis()
    }

    fun hasFreshTools(maxAgeMs: Long): Boolean {
        return _tools.value.isNotEmpty() && System.currentTimeMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean {
        if (hasFreshTools(maxAgeMs)) return false
        refreshTools()
        return true
    }

    suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> {
        return toolApi.listTools(limit = limit, offset = offset)
    }

    suspend fun refreshAgentTools(agentId: String, tools: List<Tool>) {
        _toolsByAgent.update { current -> current.toMutableMap().apply {
                    put(agentId, tools)
                } }
    }

    override suspend fun attachTool(agentId: String, toolId: String) {
        toolApi.attachTool(agentId, toolId)
        val tool = _tools.value.find { it.id == toolId }
        if (tool != null) {
            _toolsByAgent.update { current -> current.toMutableMap().apply {
                        val existing = get(agentId) ?: emptyList()
                        put(agentId, existing + tool)
                    } }
        }
    }

    override suspend fun detachTool(agentId: String, toolId: String) {
        toolApi.detachTool(agentId, toolId)
        _toolsByAgent.update { current -> current.toMutableMap().apply {
                    val existing = get(agentId) ?: emptyList()
                    put(agentId, existing.filter { it.id != toolId })
                } }
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        val tool = toolApi.upsertTool(params)
        _tools.update { current ->
            val index = current.indexOfFirst { it.id == tool.id }
            if (index >= 0) current.toMutableList().apply { this[index] = tool } else current + tool
        }
        return tool
    }

    override suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool {
        val tool = toolApi.updateTool(toolId, params)
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

    override suspend fun deleteTool(toolId: String) {
        toolApi.deleteTool(toolId)
        _tools.update { current -> current.filterNot { it.id == toolId } }
        _toolsByAgent.update { current ->
            current.mapValues { (_, tools) -> tools.filterNot { it.id == toolId } }
        }
    }
}
