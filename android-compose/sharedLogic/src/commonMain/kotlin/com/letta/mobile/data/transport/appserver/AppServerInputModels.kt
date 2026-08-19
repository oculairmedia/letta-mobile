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

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
@Serializable
sealed interface AppServerInputPayload {
    @Serializable
    @SerialName("create_message")
    data class CreateMessage(
        val messages: List<AppServerInputMessage>,
        @SerialName("client_tool_allowlist") val clientToolAllowlist: List<String>? = null,
        @SerialName("external_tool_scope_ids") val externalToolScopeIds: List<String>? = null,
    ) : AppServerInputPayload

    @Serializable
    @SerialName("approval_response")
    data class ApprovalResponse(
        @SerialName("request_id") val requestId: String,
        val decision: AppServerApprovalResponseDecision? = null,
        val error: String? = null,
    ) : AppServerInputPayload
}

@Serializable
data class AppServerInputMessage(
    val role: String,
    val content: JsonElement,
    @SerialName("client_message_id") val clientMessageId: String? = null,
) {
    companion object {
        fun userText(text: String, clientMessageId: String? = null): AppServerInputMessage =
            AppServerInputMessage(
                role = "user",
                content = JsonPrimitive(text),
                clientMessageId = clientMessageId,
            )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("behavior")
@Serializable
sealed interface AppServerApprovalResponseDecision {
    @Serializable
    @SerialName("allow")
    data class Allow(
        val message: String? = null,
        @SerialName("updated_input") val updatedInput: JsonObject? = null,
        @SerialName("selected_permission_suggestion_ids") val selectedPermissionSuggestionIds: List<String>? = null,
    ) : AppServerApprovalResponseDecision

    @Serializable
    @SerialName("deny")
    data class Deny(
        val message: String,
    ) : AppServerApprovalResponseDecision
}

@Serializable
data class AppServerExternalToolResult(
    val content: List<AppServerExternalToolResultContent>,
    @SerialName("is_error") val isError: Boolean? = null,
)

@Serializable
data class AppServerExternalToolResultContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class AppServerCreatedRuntimeEntities(
    val agent: Boolean,
    val conversation: Boolean,
)

@Serializable
data class AppServerLoopStatus(
    val status: String,
    @SerialName("active_run_ids") val activeRunIds: List<String> = emptyList(),
)

