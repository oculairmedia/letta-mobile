package com.letta.mobile.data.repository.api

import com.letta.mobile.data.backend.NoAppServerRouteException
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolSchemaGenerateParams
import com.letta.mobile.data.model.ToolUpdateParams

/**
 * Tool library operations a non-App-Server backend would serve. letta-mobile-ckmub.4: this is
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
    ): List<Tool> = noRoute("listTools")

    suspend fun getTool(toolId: String): Tool = noRoute("getTool")

    suspend fun countTools(): Int = noRoute("countTools")

    suspend fun createTool(params: ToolCreateParams): Tool = noRoute("createTool")

    suspend fun upsertTool(params: ToolCreateParams): Tool = noRoute("upsertTool")

    suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool = noRoute("updateTool")

    suspend fun generateJsonSchema(params: ToolSchemaGenerateParams): kotlinx.serialization.json.JsonObject =
        noRoute("generateJsonSchema")

    suspend fun deleteTool(toolId: String): Unit = noRoute("deleteTool")

    suspend fun attachTool(agentId: String, toolId: String): Unit = noRoute("attachTool")

    suspend fun detachTool(agentId: String, toolId: String): Unit = noRoute("detachTool")
}

private fun noRoute(operation: String): Nothing =
    throw NoAppServerRouteException("ToolRemoteSource.$operation")
