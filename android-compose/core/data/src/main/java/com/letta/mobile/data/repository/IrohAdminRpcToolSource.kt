package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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

    suspend fun listTools(): List<Tool> {
        val response = channelTransport.adminRpc(
            method = "tool.list",
            path = "/v1/tools",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Tool.serializer()), result)
    }

    suspend fun createTool(params: ToolCreateParams): Tool {
        val response = channelTransport.adminRpc(
            method = "tool.create",
            path = "/v1/tools",
            body = json.encodeToString(ToolCreateParams.serializer(), params),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.create failed")
        val result = response.result ?: error("Iroh admin_rpc tool.create returned no result")
        return json.decodeFromJsonElement(Tool.serializer(), result)
    }

    suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool {
        // Merge tool_id with params by parsing params and adding tool_id
        val paramsJson = json.encodeToString(ToolUpdateParams.serializer(), params)
        val parsed = json.parseToJsonElement(paramsJson) as? kotlinx.serialization.json.JsonObject
            ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val requestBody = buildJsonObject {
            put("tool_id", toolId)
            parsed.forEach { (key, value) -> put(key, value) }
        }
        val response = channelTransport.adminRpc(
            method = "tool.update",
            path = "/v1/tools/$toolId",
            body = requestBody.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.update failed")
        val result = response.result ?: error("Iroh admin_rpc tool.update returned no result")
        return json.decodeFromJsonElement(Tool.serializer(), result)
    }

    suspend fun deleteTool(toolId: String) {
        val params = buildJsonObject { put("tool_id", toolId) }
        val response = channelTransport.adminRpc(
            method = "tool.delete",
            path = "/v1/tools/$toolId",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.delete failed")
    }

    suspend fun attachTool(agentId: String, toolId: String) {
        val response = channelTransport.adminRpc(
            method = "tool.attach",
            path = "/v1/agents/$agentId/tools/attach/$toolId",
            body = buildJsonObject {
                put("agent_id", agentId)
                put("tool_id", toolId)
            }.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.attach failed")
    }

    suspend fun detachTool(agentId: String, toolId: String) {
        val response = channelTransport.adminRpc(
            method = "tool.detach",
            path = "/v1/agents/$agentId/tools/detach/$toolId",
            body = buildJsonObject {
                put("agent_id", agentId)
                put("tool_id", toolId)
            }.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc tool.detach failed")
    }
}
