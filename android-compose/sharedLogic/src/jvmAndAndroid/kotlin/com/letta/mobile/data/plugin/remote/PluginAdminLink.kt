package com.letta.mobile.data.plugin.remote

import com.letta.mobile.data.plugin.catalog.PluginAdminRpc
import com.letta.mobile.data.plugin.catalog.PluginCatalogResult
import com.letta.mobile.data.repository.IrohAdminRpcClient
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The part of an Iroh connection the plugin clients use: one admin RPC, and what the host advertised. */
interface PluginAdminLink {
    val hostCapabilities: Set<String>

    suspend fun adminRpc(method: String, params: JsonObject): AppServerInboundFrame.AdminRpcResponse
}

/** This client's Iroh connection as a [PluginAdminLink]. */
fun IrohAdminRpcClient.asPluginAdminLink(): PluginAdminLink = object : PluginAdminLink {
    override val hostCapabilities: Set<String> get() = channelTransport.hostCapabilities

    override suspend fun adminRpc(method: String, params: JsonObject) =
        channelTransport.adminRpc(method, PluginAdminRpc.PATH, params.toString())
}

/**
 * letta-mobile-cdgpr.8: a [PluginAdminLink] over whichever channel transport is current. [transport]
 * is read on every call, so a host that swaps transports (a reconnect, a backend switch) is followed.
 * With no transport the host advertises nothing, and no call is sent.
 */
fun pluginAdminLinkOver(transport: () -> IChannelTransport?): PluginAdminLink = object : PluginAdminLink {
    override val hostCapabilities: Set<String> get() = transport()?.hostCapabilities.orEmpty()

    override suspend fun adminRpc(method: String, params: JsonObject): AppServerInboundFrame.AdminRpcResponse {
        val channel = transport() ?: throw IllegalStateException("Not connected to a host")
        return channel.adminRpc(method, PluginAdminRpc.PATH, params.toString())
    }
}

/**
 * Puts a package's bytes in the host's asset store (the canvas relay's `AssetPut`) and answers
 * its asset ref (`sha256:<hex>`); throws when it cannot. The host installs from that store.
 */
fun interface PluginPackageUploader {
    suspend fun upload(bytes: ByteArray, sha256: String): String
}

/** Whether the host runs plugins (advertises `plugins_v1`); a client never sends `plugins.*` otherwise. */
val PluginAdminLink.supportsPlugins: Boolean get() = PluginAdminRpc.CAPABILITY in hostCapabilities

/** The reason every call answers on a host without `plugins_v1`. */
const val PLUGINS_UNSUPPORTED: String = "This host does not run plugins: turn them on, or update its Letta Mobile wrapper."

/** One `plugins.*` call: the host's result, or its refusal (or the connection's failure) as the reason. */
internal suspend fun PluginAdminLink.call(method: String, params: JsonObject = JsonObject(emptyMap())): PluginCatalogResult<JsonElement> {
    if (!supportsPlugins) return PluginCatalogResult.Refused(PLUGINS_UNSUPPORTED)
    val response = try {
        adminRpc(method, params)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return PluginCatalogResult.Refused(e.message ?: "the host could not be reached")
    }
    if (!response.success) return PluginCatalogResult.Refused(response.error ?: "$method failed on the host")
    return PluginCatalogResult.Ok(response.result ?: JsonNull)
}

/** [this] result's value read with [read], or the refusal unchanged. */
internal inline fun <S, T> PluginCatalogResult<S>.read(read: (S) -> T): PluginCatalogResult<T> = when (this) {
    is PluginCatalogResult.Refused -> this
    is PluginCatalogResult.Ok -> try {
        PluginCatalogResult.Ok(read(value))
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException: an answer this client cannot read.
        PluginCatalogResult.Refused("the host's answer could not be read: ${e.message}")
    }
}
