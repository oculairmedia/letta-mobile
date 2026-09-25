package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.APP_SERVER_PROVIDER_TARGET_LOCAL
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectProviderEntry
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `provider.list` row shape: the upstream `ConnectProviderEntry` verbatim
 * (id, display_name, description, provider_type, provider_name,
 * provider_names, is_oauth, oauth_provider_id, requires_api_key, fields,
 * auth_methods, connected, connected_providers) PLUS the legacy Letta
 * `Provider` keys older clients decode — `name`, `provider_category`,
 * `base_url`, `region` — and a flat `is_connected` convenience flag.
 * The legacy keys never shadow an upstream key.
 */
internal object ProviderCatalogProjection {
    private const val PROVIDER_CATEGORY_BYOK = "byok"

    fun toAdminArray(entries: List<AppServerConnectProviderEntry>): JsonArray =
        JsonArray(entries.map(::toAdminRow))

    fun toAdminRow(entry: AppServerConnectProviderEntry): JsonObject {
        val upstream = AppServerProtocol.json.encodeToJsonElement(AppServerConnectProviderEntry.serializer(), entry)
            as JsonObject
        val legacy = linkedMapOf<String, JsonElement>(
            "name" to JsonPrimitive(entry.providerName),
            "provider_category" to JsonPrimitive(PROVIDER_CATEGORY_BYOK),
            "base_url" to (entry.connected.baseUrl?.let(::JsonPrimitive) ?: JsonNull),
            "region" to (entry.connected.region?.let(::JsonPrimitive) ?: JsonNull),
            "is_connected" to JsonPrimitive(entry.connected.isConnected),
        )
        return JsonObject(upstream + legacy.filterKeys { it !in upstream })
    }
}

/** Decodes `provider.connect` params into the upstream `connect_provider` command. */
internal object ProviderConnectParams {
    fun toCommand(params: JsonObject?, requestId: String): AppServerCommand.ConnectProvider =
        AppServerCommand.ConnectProvider(
            requestId = requestId,
            target = APP_SERVER_PROVIDER_TARGET_LOCAL,
            providerId = params.requireParam(AdminParamKey("provider_id")),
            authMethodId = param(params, AdminParamKey("auth_method_id")),
            fields = stringFields(params?.get("fields")),
            providerName = param(params, AdminParamKey("provider_name")),
            oauthConfig = params?.get("oauth_config") as? JsonObject,
        )

    /** `fields` is `Record<string, string>` upstream; anything else is a caller error. */
    private fun stringFields(element: JsonElement?): Map<String, String> {
        if (element == null || element is JsonNull) return emptyMap()
        val obj = element as? JsonObject ?: adminError("fields must be an object of strings")
        return obj.mapValues { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: adminError("fields.$key must be a string")
        }
    }
}
