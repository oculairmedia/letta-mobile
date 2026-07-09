package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Identity reads over the Iroh admin RPC control channel.
 *
 * P4 purity client batch (letta-mobile): server handlers exist (IdentityAdminHandlers
 * registers identity.list, identity.get); this is the client wiring.
 */
class IrohAdminRpcIdentitySource(
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

    suspend fun listIdentities(): List<Identity> {
        val response = channelTransport.adminRpc(
            method = "identity.list",
            path = "/v1/identities",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc identity.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Identity.serializer()), result)
    }

    suspend fun getIdentity(identityId: String): Identity {
        val params = buildJsonObject { put("identity_id", identityId) }
        val response = channelTransport.adminRpc(
            method = "identity.get",
            path = "/v1/identities/$identityId",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc identity.get failed")
        val result = response.result ?: error("Iroh admin_rpc identity.get returned no result")
        return json.decodeFromJsonElement(Identity.serializer(), result)
    }
}
