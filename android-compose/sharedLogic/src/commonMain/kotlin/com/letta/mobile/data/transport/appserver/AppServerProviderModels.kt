package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Provider-connect and model-update wire models, mirrored field-for-field from
 * `@letta-ai/letta-code` 0.32.17 `dist/types/types/protocol_v2.d.ts`
 * (`ConnectProviderField`, `ConnectProviderAuthMethod`,
 * `ConnectProviderConnectionState`, `ConnectProviderEntry`,
 * `UpdateModelPayload`) and `runtime-scope.d.ts` (`ConversationRuntimeScope`).
 *
 * Upstream uses camelCase only inside `fields[].key` values (e.g. `apiKey`,
 * `baseUrl`); every object key on the wire is snake_case.
 */

/** The only provider store the 0.32.x App Server supports (`ConnectProviderStorageTarget`). */
const val APP_SERVER_PROVIDER_TARGET_LOCAL: String = "local"

/**
 * `ConversationRuntimeScope` = `RuntimeScope<string | null>`: unlike
 * [AppServerRuntimeScope], the agent id may be null on the wire (a
 * conversation-only scope), so responses decode it as nullable.
 */
@Serializable
data class AppServerConversationRuntimeScope(
    @SerialName("agent_id") val agentId: String?,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("acting_user_id") val actingUserId: String? = null,
)

/** One input a provider's connect form asks for (`ConnectProviderField`). */
@Serializable
data class AppServerConnectProviderField(
    val key: String,
    val label: String,
    val placeholder: String? = null,
    val secret: Boolean? = null,
    val required: Boolean? = null,
)

/** One way to authenticate a provider (`ConnectProviderAuthMethod`). */
@Serializable
data class AppServerConnectProviderAuthMethod(
    val id: String,
    val label: String,
    val description: String,
    val fields: List<AppServerConnectProviderField>,
)

/**
 * `ConnectProviderConnectionState`. `timeout` is `number | false` upstream, so
 * it stays a raw [JsonElement] instead of guessing one Kotlin type.
 */
@Serializable
data class AppServerConnectProviderConnectionState(
    @SerialName("is_connected") val isConnected: Boolean,
    val id: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("provider_type") val providerType: String? = null,
    @SerialName("auth_type") val authType: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val timeout: JsonElement? = null,
    val region: String? = null,
)

/** One connectable provider row (`ConnectProviderEntry`). */
@Serializable
data class AppServerConnectProviderEntry(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val description: String,
    @SerialName("provider_type") val providerType: String,
    @SerialName("provider_name") val providerName: String,
    @SerialName("provider_names") val providerNames: List<String> = emptyList(),
    @SerialName("is_oauth") val isOauth: Boolean? = null,
    @SerialName("oauth_provider_id") val oauthProviderId: String? = null,
    @SerialName("requires_api_key") val requiresApiKey: Boolean,
    val fields: List<AppServerConnectProviderField>? = null,
    @SerialName("auth_methods") val authMethods: List<AppServerConnectProviderAuthMethod>? = null,
    /** First connected provider, preserved upstream for older clients. */
    val connected: AppServerConnectProviderConnectionState,
    /** All connected provider aliases represented by this row. */
    @SerialName("connected_providers") val connectedProviders: List<AppServerConnectProviderConnectionState> = emptyList(),
)

/**
 * `UpdateModelPayload`. [reasoningEffort] is a raw element because upstream
 * distinguishes an ABSENT key (leave effort alone) from an explicit `null`
 * (restore the provider default) — build it with [reasoningEffortOf] /
 * [RESTORE_DEFAULT_REASONING_EFFORT] so the explicit null survives
 * `explicitNulls = false` encoding (a [JsonNull] value is a non-null Kotlin
 * object, so it is written).
 */
@Serializable
data class AppServerUpdateModelPayload(
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("model_handle") val modelHandle: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: JsonElement? = null,
) {
    companion object {
        /** Upstream `reasoning_effort` literals. */
        val REASONING_EFFORTS: List<String> = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        /** Explicit `null`: restore the provider default effort. */
        val RESTORE_DEFAULT_REASONING_EFFORT: JsonElement = JsonNull

        /** A named effort, validated against [REASONING_EFFORTS]. */
        fun reasoningEffortOf(value: String): JsonElement {
            require(value in REASONING_EFFORTS) { "unknown reasoning_effort: $value" }
            return JsonPrimitive(value)
        }
    }
}
