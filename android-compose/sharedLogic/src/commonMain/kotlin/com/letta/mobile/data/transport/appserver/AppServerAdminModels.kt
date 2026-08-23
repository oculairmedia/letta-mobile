package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AppServerChannelAccountPatch(
    val enabled: Boolean? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("dm_policy") val dmPolicy: String? = null,
    @SerialName("allowed_users") val allowedUsers: List<String>? = null,
)

/**
 * One channel as reported by `channels_list` / `channel_start` (upstream
 * `mapChannelSummary`). Unknown additive keys are tolerated by
 * [AppServerProtocol.json]; the raw envelope is preserved on
 * [AppServerReceivedFrame.raw].
 */
@Serializable
data class AppServerChannelSummary(
    @SerialName("channel_id") val channelId: String,
    @SerialName("display_name") val displayName: String? = null,
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val running: Boolean = false,
    @SerialName("dm_policy") val dmPolicy: String? = null,
    @SerialName("pending_pairings_count") val pendingPairingsCount: Int? = null,
    @SerialName("approved_users_count") val approvedUsersCount: Int? = null,
    @SerialName("routes_count") val routesCount: Int? = null,
    @SerialName("config_schema") val configSchema: JsonElement? = null,
)

/**
 * One channel account as reported by `channel_accounts_list` (upstream
 * `mapChannelAccount`).
 *
 * SECURITY (lgns8.23 landmine 2): [config] is the plugin's account config
 * VERBATIM — for the Matrix plugin that includes `accessToken` and
 * `syncAccessToken` in cleartext. [toString] is overridden so an accidental
 * string interpolation of this object (or of any frame containing it) can never
 * leak a credential; use [redactedConfig] when a diagnostic genuinely needs the
 * shape.
 */
@Serializable
data class AppServerChannelAccount(
    @SerialName("channel_id") val channelId: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("display_name") val displayName: String? = null,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val running: Boolean = false,
    @SerialName("dm_policy") val dmPolicy: String? = null,
    @SerialName("allowed_users") val allowedUsers: List<String>? = null,
    val config: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** Credential-redacted view of [config], for diagnostics that need shape. */
    fun redactedConfig(): JsonElement? = config?.let(AppServerProtocol::redactCredentials)

    override fun toString(): String =
        "AppServerChannelAccount(channelId=$channelId, accountId=$accountId, " +
            "enabled=$enabled, configured=$configured, running=$running, " +
            "config=<${config?.size ?: 0} keys withheld>)"
}

/**
 * Server capabilities and version info returned by `app_server_info_response`
 * (lgns8.24). Used for capability discovery before using protocol features.
 *
 * @param lettaCodeVersion The Letta Code version string (e.g. "0.29.12")
 * @param protocolVersion Numeric protocol version for feature detection
 * @param backend The active backend (e.g. "local", "self-hosted", "hosted")
 * @param capabilities Map of capability flags (e.g. "runtime_start" -> true,
 *   "split_channels" -> false). Current App Server versions report
 *   `split_channels: false`.
 */
@Serializable
data class AppServerInfoData(
    @SerialName("letta_code_version") val lettaCodeVersion: String? = null,
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    val backend: String? = null,
    val capabilities: JsonObject? = null,
) {
    /** Convenience accessor for the `split_channels` capability flag. */
    val splitChannels: Boolean
        get() = capabilities?.get("split_channels")?.jsonPrimitive?.booleanOrNull ?: false

    /** Convenience accessor for the `runtime_start` capability flag. */
    val hasRuntimeStart: Boolean
        get() = capabilities?.get("runtime_start")?.jsonPrimitive?.booleanOrNull ?: true

    /** Returns a specific capability flag value. */
    fun hasCapability(name: String): Boolean =
        capability(name) == true

    /** Returns an advertised capability flag, or null when absent/malformed. */
    fun capability(name: String): Boolean? =
        (capabilities?.get(name) as? JsonPrimitive)?.booleanOrNull
}

