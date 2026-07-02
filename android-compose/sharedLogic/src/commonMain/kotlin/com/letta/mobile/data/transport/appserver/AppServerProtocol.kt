package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AppServerProtocol {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encodeCommand(command: AppServerCommand): String =
        json.encodeToString(AppServerCommand.serializer(), command)

    fun decodeFrame(rawJson: String, channel: AppServerChannel): AppServerReceivedFrame {
        val element = json.parseToJsonElement(rawJson)
        val raw = element.jsonObject
        val type = raw["type"]?.jsonPrimitive?.content
        val frame = when (type) {
            "auth_response" -> json.decodeFromJsonElement<AppServerInboundFrame.AuthResponse>(element)
            "runtime_start_response" -> json.decodeFromJsonElement<AppServerInboundFrame.RuntimeStartResponse>(element)
            "sync_response" -> json.decodeFromJsonElement<AppServerInboundFrame.SyncResponse>(element)
            "abort_message_response" -> json.decodeFromJsonElement<AppServerInboundFrame.AbortMessageResponse>(element)
            "stream_delta" -> json.decodeFromJsonElement<AppServerInboundFrame.StreamDelta>(element)
            "update_loop_status" -> json.decodeFromJsonElement<AppServerInboundFrame.UpdateLoopStatus>(element)
            "update_device_status" -> json.decodeFromJsonElement<AppServerInboundFrame.UpdateDeviceStatus>(element)
            "update_queue" -> json.decodeFromJsonElement<AppServerInboundFrame.UpdateQueue>(element)
            "update_subagent_state" -> json.decodeFromJsonElement<AppServerInboundFrame.UpdateSubagentState>(element)
            "external_tool_call_request" -> json.decodeFromJsonElement<AppServerInboundFrame.ExternalToolCallRequest>(element)
            "control_request" -> json.decodeFromJsonElement<AppServerInboundFrame.ControlRequest>(element)
            "admin_rpc_response" -> json.decodeFromJsonElement<AppServerInboundFrame.AdminRpcResponse>(element)
            else -> AppServerInboundFrame.Unknown(type = type, raw = raw)
        }
        return AppServerReceivedFrame(channel = channel, frame = frame, raw = raw)
    }
}

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

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed interface AppServerCommand {
    @Serializable
    @SerialName("auth")
    data class Auth(
        @SerialName("request_id") val requestId: String,
        val token: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("runtime_start")
    data class RuntimeStart(
        @SerialName("request_id") val requestId: String,
        @SerialName("agent_id") val agentId: String? = null,
        @SerialName("create_agent") val createAgent: AppServerRuntimeStartCreateAgentOptions? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("create_conversation") val createConversation: AppServerRuntimeStartCreateConversationOptions? = null,
        val cwd: String? = null,
        val mode: AppServerPermissionMode? = null,
        @SerialName("client_info") val clientInfo: AppServerRuntimeStartClientInfo? = null,
        @SerialName("recover_approvals") val recoverApprovals: Boolean? = null,
        @SerialName("force_device_status") val forceDeviceStatus: Boolean? = null,
        @SerialName("external_tools") val externalTools: List<AppServerExternalToolsGroup>? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("input")
    data class Input(
        val runtime: AppServerRuntimeScope,
        val payload: AppServerInputPayload,
    ) : AppServerCommand

    @Serializable
    @SerialName("sync")
    data class Sync(
        val runtime: AppServerRuntimeScope,
        @SerialName("request_id") val requestId: String? = null,
        @SerialName("recover_approvals") val recoverApprovals: Boolean? = null,
        @SerialName("force_device_status") val forceDeviceStatus: Boolean? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("abort_message")
    data class AbortMessage(
        val runtime: AppServerRuntimeScope,
        @SerialName("request_id") val requestId: String? = null,
        @SerialName("run_id") val runId: String? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("external_tool_call_response")
    data class ExternalToolCallResponse(
        @SerialName("request_id") val requestId: String,
        val result: AppServerExternalToolResult? = null,
        val error: String? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("admin_rpc")
    data class AdminRpc(
        @SerialName("request_id") val requestId: String,
        val method: String,
        val params: JsonObject? = null,
    ) : AppServerCommand
}

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

@Serializable
sealed interface AppServerInboundFrame {
    val type: String?
    val requestId: String?
    val runtime: AppServerRuntimeScope?

    @Serializable
    @SerialName("auth_response")
    data class AuthResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "auth_response"

        @Transient
        override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("runtime_start_response")
    data class RuntimeStartResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        override val runtime: AppServerRuntimeScope? = null,
        val agent: JsonObject? = null,
        val conversation: JsonObject? = null,
        val created: AppServerCreatedRuntimeEntities? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "runtime_start_response"
    }

    @Serializable
    @SerialName("sync_response")
    data class SyncResponse(
        @SerialName("request_id") override val requestId: String,
        override val runtime: AppServerRuntimeScope,
        val success: Boolean,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "sync_response"
    }

    @Serializable
    @SerialName("abort_message_response")
    data class AbortMessageResponse(
        @SerialName("request_id") override val requestId: String,
        override val runtime: AppServerRuntimeScope,
        val aborted: Boolean,
        val success: Boolean,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "abort_message_response"
    }

    @Serializable
    @SerialName("stream_delta")
    data class StreamDelta(
        override val runtime: AppServerRuntimeScope,
        @SerialName("event_seq") val eventSeq: Long,
        @SerialName("emitted_at") val emittedAt: String,
        @SerialName("idempotency_key") val idempotencyKey: String,
        val delta: JsonElement,
        @SerialName("subagent_id") val subagentId: String? = null,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "stream_delta"

        @Transient
        override val requestId: String? = null
    }

    @Serializable
    @SerialName("update_loop_status")
    data class UpdateLoopStatus(
        override val runtime: AppServerRuntimeScope,
        @SerialName("event_seq") val eventSeq: Long,
        @SerialName("emitted_at") val emittedAt: String,
        @SerialName("idempotency_key") val idempotencyKey: String,
        @SerialName("loop_status") val loopStatus: AppServerLoopStatus,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "update_loop_status"

        @Transient
        override val requestId: String? = null
    }

    @Serializable
    @SerialName("update_device_status")
    data class UpdateDeviceStatus(
        override val runtime: AppServerRuntimeScope,
        @SerialName("event_seq") val eventSeq: Long,
        @SerialName("emitted_at") val emittedAt: String,
        @SerialName("idempotency_key") val idempotencyKey: String,
        @SerialName("device_status") val deviceStatus: JsonObject,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "update_device_status"

        @Transient
        override val requestId: String? = null
    }

    @Serializable
    @SerialName("update_queue")
    data class UpdateQueue(
        override val runtime: AppServerRuntimeScope,
        @SerialName("event_seq") val eventSeq: Long,
        @SerialName("emitted_at") val emittedAt: String,
        @SerialName("idempotency_key") val idempotencyKey: String,
        val queue: List<JsonObject>,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "update_queue"

        @Transient
        override val requestId: String? = null
    }

    @Serializable
    @SerialName("update_subagent_state")
    data class UpdateSubagentState(
        override val runtime: AppServerRuntimeScope,
        @SerialName("event_seq") val eventSeq: Long,
        @SerialName("emitted_at") val emittedAt: String,
        @SerialName("idempotency_key") val idempotencyKey: String,
        val subagents: List<JsonObject>,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "update_subagent_state"

        @Transient
        override val requestId: String? = null
    }

    @Serializable
    @SerialName("external_tool_call_request")
    data class ExternalToolCallRequest(
        @SerialName("request_id") override val requestId: String,
        override val runtime: AppServerRuntimeScope? = null,
        @SerialName("scope_id") val scopeId: String? = null,
        @SerialName("tool_call_id") val toolCallId: String,
        @SerialName("tool_name") val toolName: String,
        val input: JsonObject,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "external_tool_call_request"
    }

    @Serializable
    @SerialName("control_request")
    data class ControlRequest(
        @SerialName("request_id") override val requestId: String,
        val request: JsonObject,
        @SerialName("agent_id") val agentId: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "control_request"

        @Transient
        override val runtime: AppServerRuntimeScope? =
            if (agentId != null && conversationId != null) {
                AppServerRuntimeScope(agentId = agentId, conversationId = conversationId)
            } else {
                null
            }
    }

    @Serializable
    @SerialName("admin_rpc_response")
    data class AdminRpcResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val result: JsonElement? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = "admin_rpc_response"

        @Transient
        override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    data class Unknown(
        override val type: String?,
        val raw: JsonObject,
    ) : AppServerInboundFrame {
        override val requestId: String?
            get() = raw["request_id"]?.jsonPrimitive?.content

        override val runtime: AppServerRuntimeScope?
            get() = null
    }
}
