package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.repository.api.ArchiveIrohSource
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Archive list over the Iroh admin RPC control channel.
 *
 * Previously inlined via [IrohAdminRpcClient.callList]; kept as a dedicated
 * source so [CachedArchiveRepository] can depend on [ArchiveIrohSource].
 */
class IrohAdminRpcArchiveSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    },
) : ArchiveIrohSource {
    override fun shouldUseIroh(): Boolean =
        settingsRepository.activeBackendIsIroh()

    override suspend fun listArchives(): List<Archive> {
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
