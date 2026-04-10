package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpServer(
    val id: String,
    @SerialName("server_name") val serverName: String,
    @SerialName("server_url") val serverUrl: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    @SerialName("mcp_server_type") val mcpServerType: String? = null,
    val config: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class McpServerCreateParams(
    @SerialName("server_name") val serverName: String,
    val config: JsonObject,
)

@Serializable
data class McpServerUpdateParams(
    @SerialName("server_name") val serverName: String? = null,
    val config: JsonObject? = null,
)
