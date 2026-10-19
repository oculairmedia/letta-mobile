package com.letta.mobile.data.repository.api

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolSchemaGenerateParams
import com.letta.mobile.data.model.ToolUpdateParams

/**
 * Tool library operations a non-App-Server backend would serve. this is
 * the seam for one; there is no production implementation since the Letta REST backend was
 * removed, so [com.letta.mobile.data.repository.ToolRepository] binds no route and serves tools
 * over an Iroh host's `admin_rpc` relay.
 * Every call fails unless overridden.
 */
interface ToolRemoteSource {
    suspend fun listTools(
        tags: List<String>? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): List<Tool> = noRoute()

    suspend fun getTool(toolId: ToolId): Tool = noRoute()

    suspend fun countTools(): Int = noRoute()

    suspend fun createTool(params: ToolCreateParams): Tool = noRoute()

    suspend fun upsertTool(params: ToolCreateParams): Tool = noRoute()

    suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool = noRoute()

    suspend fun generateJsonSchema(params: ToolSchemaGenerateParams): kotlinx.serialization.json.JsonObject =
        noRoute()

    suspend fun deleteTool(toolId: ToolId): Unit = noRoute()

    suspend fun attachTool(agentId: AgentId, toolId: ToolId): Unit = noRoute()

    suspend fun detachTool(agentId: AgentId, toolId: ToolId): Unit = noRoute()
}

private fun noRoute(): Nothing = throw AppServerRouteUnavailable(SessionRoute.TOOLS, "ToolRemoteSource")
