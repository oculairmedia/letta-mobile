package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class IrohAdminRpcPassageSource(
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
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

    suspend fun listPassages(agentId: String): List<Passage> {
        val params = buildJsonObject { put("agent_id", agentId) }
        val response = channelTransport.adminRpc(
            method = "passage.list",
            path = "/v1/agents/$agentId/passages",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc passage.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Passage.serializer()), result)
    suspend fun createPassage(agentId: String, text: String): Passage {
        val response = channelTransport.adminRpc(
            method = "passage.create",
            path = "/v1/agents/$agentId/archival-memory",
            body = buildJsonObject {
                put("agent_id", agentId)
                put("text", text)
            }.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc passage.create failed")
        val result = response.result ?: error("Iroh admin_rpc passage.create returned no result")
        return json.decodeFromJsonElement(Passage.serializer(), result)
    }

    suspend fun deletePassage(agentId: String, passageId: String) {
        val response = channelTransport.adminRpc(
            method = "passage.delete",
            path = "/v1/agents/$agentId/archival-memory/$passageId",
            body = buildJsonObject {
                put("agent_id", agentId)
                put("passage_id", passageId)
            }.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc passage.delete failed")
    }
}
