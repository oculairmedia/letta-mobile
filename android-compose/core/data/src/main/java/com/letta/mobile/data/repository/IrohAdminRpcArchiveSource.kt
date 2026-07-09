package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class IrohAdminRpcArchiveSource(
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

    suspend fun listArchives(): List<Archive> {
        val response = channelTransport.adminRpc(
            method = "archive.list",
            path = "/v1/archives",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc archive.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Archive.serializer()), result)
    }
}
