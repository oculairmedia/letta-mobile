package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.APP_SERVER_PROVIDER_TARGET_LOCAL
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectProviderEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * App Server provider management over admin_rpc (letta-mobile-w4q4p).
 *
 * `provider.list` used to be a controller constant (one `lmstudio-local` row
 * ported from admin-shim). It now answers from the live
 * `list_connect_providers` command, so clients see every provider the App
 * Server can connect, which of them are connected, and the auth methods/fields
 * a connect form needs. `provider.connect` / `provider.disconnect` write the
 * App Server's LOCAL provider store through `connect_provider` /
 * `disconnect_provider` — the App Server stays that store's only writer.
 *
 * Wire shape: `provider.list` keeps returning a bare array of provider objects
 * (the legacy `Provider` decoder still reads it); see [ProviderCatalogProjection].
 */
internal object ProviderAdminHandlers {
    val METHODS: Set<String> = setOf("provider.list", "provider.connect", "provider.disconnect")

    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        router.register("provider.list") { listProviders(nativeClient) }
        router.register("provider.connect") { params -> connect(nativeClient, params) }
        router.register("provider.disconnect") { params -> disconnect(nativeClient, params) }
    }

    private suspend fun listProviders(nativeClient: AppServerClient?): JsonArray =
        NativeAdmin.require(nativeClient, NativeAdminOp.ProviderList) { c ->
            val response = c.listConnectProviders(
                AppServerCommand.ListConnectProviders(NativeAdmin.requestId(), APP_SERVER_PROVIDER_TARGET_LOCAL),
            )
            if (!response.success) adminError(response.error ?: "list_connect_providers failed")
            ModelControlTelemetry.providerListed(response.providers)
            ProviderCatalogProjection.toAdminArray(response.providers)
        }

    private suspend fun connect(nativeClient: AppServerClient?, params: JsonObject?): JsonObject {
        val command = ProviderConnectParams.toCommand(params, NativeAdmin.requestId())
        return NativeAdmin.require(nativeClient, NativeAdminOp.ProviderConnect) { c ->
            val response = c.connectProvider(command)
            ModelControlTelemetry.providerMutation("provider.connect", command.providerId, response.success)
            if (!response.success) adminError(response.error ?: "connect_provider failed")
            mutationResult(response.providers, response.modelsMayHaveChanged)
        }
    }

    private suspend fun disconnect(nativeClient: AppServerClient?, params: JsonObject?): JsonObject {
        val command = AppServerCommand.DisconnectProvider(
            requestId = NativeAdmin.requestId(),
            target = APP_SERVER_PROVIDER_TARGET_LOCAL,
            providerId = params.requireParam(AdminParamKey("provider_id")),
            providerName = param(params, AdminParamKey("provider_name")),
        )
        return NativeAdmin.require(nativeClient, NativeAdminOp.ProviderDisconnect) { c ->
            val response = c.disconnectProvider(command)
            ModelControlTelemetry.providerMutation("provider.disconnect", command.providerId, response.success)
            if (!response.success) adminError(response.error ?: "disconnect_provider failed")
            mutationResult(response.providers, response.modelsMayHaveChanged)
        }
    }

    private fun mutationResult(
        providers: List<AppServerConnectProviderEntry>,
        modelsMayHaveChanged: Boolean,
    ): JsonObject = buildJsonObject {
        put("providers", ProviderCatalogProjection.toAdminArray(providers))
        put("models_may_have_changed", modelsMayHaveChanged)
    }
}
