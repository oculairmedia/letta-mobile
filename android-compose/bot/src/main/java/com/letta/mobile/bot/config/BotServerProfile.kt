package com.letta.mobile.bot.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BotServerProfile(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("auth_token") val authToken: String? = null,
    val transport: BotConfig.Transport = BotConfig.Transport.HTTP,
    @SerialName("server_type") val serverType: ServerType = ServerType.CUSTOM,
    @SerialName("last_connected_at") val lastConnectedAt: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
) {
    @Serializable
    enum class ServerType {
        @SerialName("cloud") CLOUD,
        @SerialName("self_hosted") SELF_HOSTED,
        @SerialName("custom") CUSTOM,
    }
}
