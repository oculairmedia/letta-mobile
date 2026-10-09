package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.iroh.AdminRpcMethod
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool CRUD + agent attach/detach over the Iroh admin RPC control channel.
 *
 * P4 purity batches: server handlers in ToolAdminHandlers register tool.list,
 * tool.create, tool.update, tool.delete (client batch) and tool.attach,
 * tool.detach (server batch); this is the merged client wiring.
 */
class IrohAdminRpcToolSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    },
) {
    fun shouldUseIroh(): Boolean =
        settingsRepository.activeBackendIsIroh()

    suspend fun listTools(): List<Tool> = listToolsRpc(JsonObject(emptyMap()))

    /** One page of `tool.list`; the handler honors `limit`/`offset` (ToolAdminHandlers). */
    suspend fun listTools(limit: Int, offset: Int): List<Tool> = listToolsRpc(
        buildJsonObject {
            put("limit", limit)
            put("offset", offset)
        },
    )

    private suspend fun listToolsRpc(body: JsonObject): List<Tool> {
        val response = channelTransport.adminRpc(method = "tool.list", path = "/v1/tools", body = body.toString())
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Tool.serializer()), result)
    }

    /** `tool.get` by id; the host answers an admin error containing "not found" for an unknown id. */
    suspend fun getTool(toolId: ToolId): Tool {
        val response = channelTransport.adminRpc(
            method = "tool.get",
            path = "/v1/tools/${toolId.value}",
            body = buildJsonObject { put("tool_id", toolId.value) }.toString(),
        )
        return decodeTool(response, AdminRpcMethod("tool.get"))
    }

    suspend fun createTool(params: ToolCreateParams): Tool {
        val response = channelTransport.adminRpc(
            method = "tool.create",
            path = "/v1/tools",
            body = json.encodeToString(ToolCreateParams.serializer(), params),
        )
        return decodeTool(response, AdminRpcMethod("tool.create"))
    }

    suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool {
        // Merge tool_id with params by parsing params and adding tool_id
        val paramsJson = json.encodeToString(ToolUpdateParams.serializer(), params)
        val parsed = json.parseToJsonElement(paramsJson) as? kotlinx.serialization.json.JsonObject
            ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val requestBody = buildJsonObject {
            put("tool_id", toolId.value)
            parsed.forEach { (key, value) -> put(key, value) }
        }
        val response = channelTransport.adminRpc(
            method = "tool.update",
            path = "/v1/tools/${toolId.value}",
            body = requestBody.toString(),
        )
        return decodeTool(response, AdminRpcMethod("tool.update"))
    }

    private fun decodeTool(response: AppServerInboundFrame.AdminRpcResponse, method: AdminRpcMethod): Tool {
        if (!response.success) error(response.error ?: "Iroh admin_rpc ${method.value} failed")
        val result = response.result ?: error("Iroh admin_rpc ${method.value} returned no result")
        return json.decodeFromJsonElement(Tool.serializer(), result)
    }

    suspend fun deleteTool(toolId: ToolId) {
        val params = buildJsonObject { put("tool_id", toolId.value) }
        val response = channelTransport.adminRpc(
            method = "tool.delete",
            path = "/v1/tools/${toolId.value}",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.delete failed")
    }

    suspend fun attachTool(agentId: AgentId, toolId: ToolId) {
        val response = channelTransport.adminRpc(
            method = "tool.attach",
            path = "/v1/agents/${agentId.value}/tools/attach/${toolId.value}",
            body = buildJsonObject {
                put("agent_id", agentId.value)
                put("tool_id", toolId.value)
            }.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.attach failed")
    }

    suspend fun detachTool(agentId: AgentId, toolId: ToolId) {
        val response = channelTransport.adminRpc(
            method = "tool.detach",
            path = "/v1/agents/${agentId.value}/tools/detach/${toolId.value}",
            body = buildJsonObject {
                put("agent_id", agentId.value)
                put("tool_id", toolId.value)
            }.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.detach failed")
    }
}
