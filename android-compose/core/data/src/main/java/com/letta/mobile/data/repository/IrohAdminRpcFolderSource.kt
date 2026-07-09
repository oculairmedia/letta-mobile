package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Folder reads over the Iroh admin RPC control channel.
 *
 * P4 purity client batch (letta-mobile): server handlers exist (ArchiveAdminHandlers
 * registers folder.list with agent_id param); this is the client wiring.
 */
class IrohAdminRpcFolderSource(
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

    suspend fun listFolders(agentId: String): List<Folder> {
        val params = buildJsonObject { put("agent_id", agentId) }
        val response = channelTransport.adminRpc(
            method = "folder.list",
            path = "/v1/agents/$agentId/folders",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc folder.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Folder.serializer()), result)
    }
}
