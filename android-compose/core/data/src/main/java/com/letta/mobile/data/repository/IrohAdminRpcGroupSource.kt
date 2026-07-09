package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Group reads over the Iroh admin RPC control channel.
 *
 * P4 purity client batch (letta-mobile): server handlers exist (ArchiveAdminHandlers
 * registers group.list); this is the client wiring.
 */
class IrohAdminRpcGroupSource(
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

    suspend fun listGroups(): List<Group> {
        val response = channelTransport.adminRpc(
            method = "group.list",
            path = "/v1/groups",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc group.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Group.serializer()), result)
    }
}
