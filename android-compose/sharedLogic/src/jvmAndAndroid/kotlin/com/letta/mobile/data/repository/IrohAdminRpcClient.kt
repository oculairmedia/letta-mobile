package com.letta.mobile.data.repository

import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class IrohAdminRpcClient(
    // @PublishedApi internal: touched by the public `inline` call<T>/callList<T>
    // helpers below — inline bodies can only access public or @PublishedApi
    // members, not private ones.
    @PublishedApi internal val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository
) {
    fun shouldUseIroh(): Boolean = settingsRepository.activeBackendIsIroh()

    @PublishedApi internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    suspend inline fun <reified T> call(method: String, path: String, body: String? = null): T {
        val r = channelTransport.adminRpc(method, path, body)
        if (!r.success) error(r.error ?: "Iroh admin_rpc $method failed")
        val res = r.result ?: error("Iroh admin_rpc $method returned no result")
        return json.decodeFromJsonElement(serializer<T>(), res)
    }

    suspend fun callUnit(method: String, path: String, body: String? = null) {
        val r = channelTransport.adminRpc(method, path, body)
        if (!r.success) error(r.error ?: "Iroh admin_rpc $method failed")
    }

    suspend inline fun <reified T> callList(method: String, path: String, body: String? = null): List<T> {
        val r = channelTransport.adminRpc(method, path, body)
        if (!r.success) error(r.error ?: "Iroh admin_rpc $method failed")
        val res = r.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(serializer<T>()), res)
    }
}
