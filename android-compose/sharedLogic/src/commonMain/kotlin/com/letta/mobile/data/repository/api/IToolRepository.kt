package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Why a single-tool lookup/mutation failed, so the UI can say something more specific than a
 * generic "Failed to load tool": the tool is gone ([NOT_FOUND]) versus the active backend simply
 * has no route for it ([NOT_SUPPORTED], e.g. an HTTP-only route under iroh://).
 */
class ToolUnavailableException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason { NOT_FOUND, NOT_SUPPORTED }
}

interface IToolRepository {
    fun getTools(): StateFlow<List<Tool>>
    fun getAgentTools(agentId: AgentId): Flow<List<Tool>>
    fun getAgentTools(agentId: String): Flow<List<Tool>> = getAgentTools(AgentId(agentId))
    suspend fun countTools(): Int
    suspend fun refreshTools()
    suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean
    suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool>
    /**
     * Resolves one tool by id. Default: the cached catalog (refreshed once on a miss); throws
     * [ToolUnavailableException] with [ToolUnavailableException.Reason.NOT_FOUND] when absent.
     */
    suspend fun getTool(toolId: String): Tool {
        getTools().value.firstOrNull { it.id.value == toolId }?.let { return it }
        refreshTools()
        return getTools().value.firstOrNull { it.id.value == toolId }
            ?: throw ToolUnavailableException(ToolUnavailableException.Reason.NOT_FOUND, "Tool $toolId was not found")
    }
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
