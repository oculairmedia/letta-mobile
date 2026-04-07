package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import com.letta.mobile.data.repository.api.IToolRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRepository @Inject constructor(
    private val toolApi: ToolApi,
) : IToolRepository {
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())
    private val _toolsByAgent = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())

    fun getTools(): Flow<List<Tool>> = _tools

    fun getAgentTools(agentId: String): Flow<List<Tool>> {
        return _toolsByAgent.map { it[agentId] ?: emptyList() }
    }

    suspend fun refreshTools() {
        _tools.value = toolApi.listTools()
    }

    suspend fun refreshAgentTools(agentId: String, tools: List<Tool>) {
        _toolsByAgent.value = _toolsByAgent.value.toMutableMap().apply {
            put(agentId, tools)
        }
    }

    suspend fun attachTool(agentId: String, toolId: String) {
        toolApi.attachTool(agentId, toolId)
        val tool = _tools.value.find { it.id == toolId }
        if (tool != null) {
            _toolsByAgent.value = _toolsByAgent.value.toMutableMap().apply {
                val existing = get(agentId) ?: emptyList()
                put(agentId, existing + tool)
            }
        }
    }

    suspend fun detachTool(agentId: String, toolId: String) {
        toolApi.detachTool(agentId, toolId)
        _toolsByAgent.value = _toolsByAgent.value.toMutableMap().apply {
            val existing = get(agentId) ?: emptyList()
            put(agentId, existing.filter { it.id != toolId })
        }
    }

    suspend fun upsertTool(params: ToolCreateParams): Tool {
        val tool = toolApi.upsertTool(params)
        refreshTools()
        return tool
    }
}
