package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams

/**
 * Remote HTTP (or equivalent) tool admin surface used by
 * [com.letta.mobile.data.repository.CachedToolRepository].
 * Platform modules supply Ktor/[ToolApi] bindings; Iroh traffic goes through
 * [ToolIrohSource].
 */
interface ToolRemoteSource {
    suspend fun listTools(
        tags: List<String>? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): List<Tool>

    suspend fun countTools(): Int
    suspend fun upsertTool(params: ToolCreateParams): Tool
    suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool
    suspend fun deleteTool(toolId: String)
    suspend fun attachTool(agentId: String, toolId: String)
    suspend fun detachTool(agentId: String, toolId: String)
}

/**
 * Iroh admin_rpc tool surface. Implemented by
 * [com.letta.mobile.data.repository.IrohAdminRpcToolSource].
 *
 * Distinct from desktop [com.letta.mobile.data.repository.iroh.IrohToolRepository],
 * which talks to [com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory].
 */
interface ToolIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listTools(): List<Tool>
    suspend fun createTool(params: ToolCreateParams): Tool
    suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool
    suspend fun deleteTool(toolId: String)
    suspend fun attachTool(agentId: String, toolId: String)
    suspend fun detachTool(agentId: String, toolId: String)
}
