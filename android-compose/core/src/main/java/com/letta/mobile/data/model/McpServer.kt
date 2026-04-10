package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class McpServer(
    val id: String,
    @SerialName("server_name") val serverName: String,
    @SerialName("server_url") val serverUrl: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String>? = null,
    @SerialName("auth_header") val authHeader: String? = null,
    @SerialName("auth_token") val authToken: String? = null,
    @SerialName("token") val token: String? = null,
    @SerialName("custom_headers") val customHeaders: Map<String, String>? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("server_type") val serverType: String? = null,
    @SerialName("mcp_server_type") val mcpServerType: String? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("created_by_id") val createdById: String? = null,
    @SerialName("last_updated_by_id") val lastUpdatedById: String? = null,
    @SerialName("metadata_") val metadata: Map<String, JsonElement> = emptyMap(),
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

fun McpServer.effectiveServerType(): String? =
    type ?: serverType ?: mcpServerType ?: config.stringValue("type", "server_type", "mcp_server_type")

fun McpServer.effectiveServerUrl(): String? =
    serverUrl ?: config.stringValue("server_url")

fun McpServer.effectiveCommand(): String? =
    command ?: config.stringValue("command") ?: config.nestedObject("stdio_config")?.stringValue("command")

fun McpServer.effectiveArgs(): List<String> =
    args.takeIf { it.isNotEmpty() }
        ?: config.stringListValue("args")
        ?: config.nestedObject("stdio_config")?.stringListValue("args")
        ?: emptyList()

fun McpServer.effectiveEnv(): Map<String, String>? =
    env ?: config.stringMapValue("env") ?: config.nestedObject("stdio_config")?.stringMapValue("env")

fun McpServer.effectiveAuthHeader(): String? =
    authHeader ?: config.stringValue("auth_header")

fun McpServer.effectiveAuthToken(): String? =
    authToken ?: token ?: config.stringValue("auth_token", "token")

fun McpServer.effectiveCustomHeaders(): Map<String, String>? =
    customHeaders ?: config.stringMapValue("custom_headers")

private fun JsonObject?.nestedObject(key: String): JsonObject? = this?.get(key)?.jsonObject

private fun JsonObject?.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } }

private fun JsonObject?.stringListValue(key: String): List<String>? =
    (this?.get(key)?.jsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }

private fun JsonObject?.stringMapValue(key: String): Map<String, String>? =
    this?.get(key)?.jsonObject?.mapValues { (_, value) -> value.jsonPrimitive.content }
