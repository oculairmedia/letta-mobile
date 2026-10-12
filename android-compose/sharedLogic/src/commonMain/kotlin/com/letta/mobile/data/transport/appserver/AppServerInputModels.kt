package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        /** `strict` (default upstream) fails the input on an unreadable image; `drop` skips it. */
        @SerialName("image_failure_mode") val imageFailureMode: String? = null,
        /** Keep interactive tools (questions, plan mode) out of this turn, e.g. for headless input. */
        @SerialName("exclude_interactive_tools") val excludeInteractiveTools: Boolean? = null,
        /** letta-code 0.34+: see [AppServerClientPreferences]. Null inherits the conversation's. */
        @SerialName("client_preferences") val clientPreferences: AppServerClientPreferences? = null,
    ) : AppServerInputPayload

    @Serializable
    @SerialName("approval_response")
    data class ApprovalResponse(
        @SerialName("request_id") val requestId: String,
        val decision: AppServerApprovalResponseDecision? = null,
        val error: String? = null,
    ) : AppServerInputPayload

    /**
     * Continues a teleported conversation on this host (`InputTeleportContinuePayload`,
     * `teleport-protocol.ts`). [continuation] stays raw: it carries upstream approval bodies.
     */
    @Serializable
    @SerialName("teleport_continue")
    data class TeleportContinue(
        @SerialName("teleport_id") val teleportId: String,
        val source: AppServerTeleportSource,
        val continuation: JsonObject? = null,
        /** letta-code 0.34+: see [AppServerClientPreferences]. Null inherits the conversation's. */
        @SerialName("client_preferences") val clientPreferences: AppServerClientPreferences? = null,
    ) : AppServerInputPayload
}

@Serializable
data class AppServerTeleportSource(
    @SerialName("device_id") val deviceId: String,
    @SerialName("connection_name") val connectionName: String,
)

/**
 * letta-code 0.34+ `ClientPreferences` (`client-preferences.ts`): client choices the server
 * remembers per conversation. On `create_message` / `teleport_continue`, omitting the field
 * inherits the stored snapshot, sending it replaces the snapshot, and an empty object (both
 * fields null) clears it. `exclude_interactive_tools` still wins over [toolset].
 */
@Serializable
data class AppServerClientPreferences(
    val toolset: AppServerToolsetPreference? = null,
) {
    companion object {
        /** `{}`: clears the conversation's remembered preferences. */
        val CLEAR: AppServerClientPreferences = AppServerClientPreferences()
    }
}

/** `ClientPreferences.toolset`: the tool names a conversation offers. */
@Serializable
data class AppServerToolsetPreference(
    val include: List<String>,
)

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

/** `change_device_state` payload; every field is optional and only sent fields change. */
@Serializable
data class AppServerDeviceStatePayload(
    val mode: AppServerPermissionMode? = null,
    val cwd: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
)

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

/**
 * `update_loop_status.loop_status`. [clientMessageIdsByRunId] and [executingToolCallIds]
 * arrived in App Server 0.32.17; both default to empty so older servers still decode
 * (letta-mobile-qygvv.8).
 */
@Serializable
data class AppServerLoopStatus(
    val status: String,
    @SerialName("active_run_ids") val activeRunIds: List<String> = emptyList(),
    /** Exact `client_message_id`s each recently observed run consumed (server insertion order). */
    @SerialName("client_message_ids_by_run_id")
    val clientMessageIdsByRunId: Map<String, List<String>> = emptyMap(),
    /** Tool call ids executing client-side; populated only while `EXECUTING_CLIENT_SIDE_TOOL`. */
    @SerialName("executing_tool_call_ids") val executingToolCallIds: List<String> = emptyList(),
)

