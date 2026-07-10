package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Provider reads over the Iroh admin RPC control channel.
 *
 * P4 purity client batch (letta-mobile): server handlers exist (ModelAdminHandlers
 * registers provider.list); this is the client wiring.
 */
class IrohAdminRpcProviderSource(
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

    suspend fun listProviders(): List<Provider> {
        val response = channelTransport.adminRpc(
            method = "provider.list",
            path = "/v1/providers",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc provider.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Provider.serializer()), result)
    }
}
