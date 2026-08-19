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
enum class AppServerChannel {
    @SerialName("control")
    Control,

    @SerialName("stream")
    Stream,
}

@Serializable
data class AppServerReceivedFrame(
    val channel: AppServerChannel,
    val frame: AppServerInboundFrame,
    val raw: JsonObject,
    /**
     * Connection generation that produced this frame (lgns8.22.4). Stamped when
     * the frame enters a stable reconnect pipe so delayed delivery cannot
     * register under a successor generation. Null for transports that do not
     * stamp (tests / direct clients) — callers fall back to the live provider.
     */
    @Transient val connectionGeneration: Long? = null,
)

@Serializable
data class AppServerRuntimeScope(
    @SerialName("agent_id") val agentId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("acting_user_id") val actingUserId: String? = null,
)

@Serializable
enum class AppServerPermissionMode {
    @SerialName("standard")
    Standard,

    @SerialName("acceptEdits")
    AcceptEdits,

    @SerialName("memory")
    Memory,

    @SerialName("unrestricted")
    Unrestricted,
}

@Serializable
data class AppServerRuntimeStartClientInfo(
    val name: String,
    val title: String? = null,
    val version: String? = null,
)

@Serializable
data class AppServerRuntimeStartCreateAgentOptions(
    val body: JsonObject,
    @SerialName("pin_global") val pinGlobal: Boolean? = null,
)

@Serializable
data class AppServerRuntimeStartCreateConversationOptions(
    val body: JsonObject? = null,
)

@Serializable
data class AppServerExternalToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val label: String? = null,
)

@Serializable
data class AppServerExternalToolsGroup(
    @SerialName("scope_id") val scopeId: String? = null,
    val tools: List<AppServerExternalToolDefinition>,
)

