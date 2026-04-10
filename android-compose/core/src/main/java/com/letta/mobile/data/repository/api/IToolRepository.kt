package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import kotlinx.coroutines.flow.Flow

interface IToolRepository {
    fun getTools(): Flow<List<Tool>>
    fun getAgentTools(agentId: String): Flow<List<Tool>>
    suspend fun countTools(): Int
    suspend fun refreshTools()
    suspend fun attachTool(agentId: String, toolId: String)
    suspend fun detachTool(agentId: String, toolId: String)
    suspend fun upsertTool(params: ToolCreateParams): Tool
    suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool
    suspend fun deleteTool(toolId: String)
}
