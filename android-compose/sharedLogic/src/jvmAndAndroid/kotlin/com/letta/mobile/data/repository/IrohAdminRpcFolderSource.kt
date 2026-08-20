package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
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
        settingsRepository.activeBackendIsIroh()

    suspend fun listFolders(name: String? = null): List<Folder> {
        val params = buildJsonObject {
            name?.let { put("name", it) }
        }
        val path = buildString {
            append("/v1/folders")
            name?.let { append("?name=$it") }
        }
        val response = channelTransport.adminRpc(
            method = "folder.list",
            path = path,
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc folder.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Folder.serializer()), result)
    }
}
