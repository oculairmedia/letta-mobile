package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IToolRepository {
    fun getTools(): StateFlow<List<Tool>>
    fun getAgentTools(agentId: AgentId): Flow<List<Tool>>
    fun getAgentTools(agentId: String): Flow<List<Tool>> = getAgentTools(AgentId(agentId))
    suspend fun countTools(): Int
    suspend fun refreshTools()
    suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean
    suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool>
    suspend fun attachTool(agentId: AgentId, toolId: ToolId)
    suspend fun attachTool(agentId: String, toolId: String) = attachTool(AgentId(agentId), ToolId(toolId))
    suspend fun detachTool(agentId: AgentId, toolId: ToolId)
    suspend fun detachTool(agentId: String, toolId: String) = detachTool(AgentId(agentId), ToolId(toolId))
    suspend fun upsertTool(params: ToolCreateParams): Tool
    suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool
    suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool = updateTool(ToolId(toolId), params)
    suspend fun deleteTool(toolId: ToolId)
    suspend fun deleteTool(toolId: String) = deleteTool(ToolId(toolId))
}
