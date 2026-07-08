package com.letta.mobile.data.repository

import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class IrohAdminRpcToolSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
) {
    fun shouldUseIroh(): Boolean =
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

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
