package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AppServerProtocol {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** Placeholder substituted for credential values in redacted diagnostics. */
    const val REDACTED_PLACEHOLDER: String = "<redacted>"

    /** Upper bound on decode-failure diagnostic length so logs/traces stay bounded. */
    const val MAX_DIAGNOSTIC_LENGTH: Int = 512

    private val redactedPrimitive = JsonPrimitive(REDACTED_PLACEHOLDER)
    private val emptyRaw = JsonObject(emptyMap())

    fun encodeCommand(command: AppServerCommand): String =
        json.encodeToString(AppServerCommand.serializer(), command)

    /**
     * Decode one inbound App Server frame. This is **total** — it never throws.
     *
     * Forward-compatibility contract (letta-mobile-lgns8.4):
     * - Unknown top-level `type` values decode to [AppServerInboundFrame.Unknown]
     *   with the raw envelope preserved, so receive loops survive new server frames.
     * - Additive object keys on known frames are ignored ([Json.ignoreUnknownKeys]).
     * - A malformed *known* frame (missing/mistyped required field) or non-object /
     *   syntactically invalid JSON becomes an explicit
     *   [AppServerInboundFrame.DecodeFailure] carrying the preserved raw envelope
     *   (when available) plus a bounded, credential-redacted diagnostic — instead of
     *   throwing and killing the receive loop.
     */
    fun decodeFrame(rawJson: String, channel: AppServerChannel): AppServerReceivedFrame {
        val element = runCatching { json.parseToJsonElement(rawJson) }.getOrNull()
        val raw = element as? JsonObject
        if (raw == null) {
            val reason = if (element == null) "invalid JSON syntax" else "top-level frame is not a JSON object"
            return AppServerReceivedFrame(
                channel = channel,
                frame = AppServerInboundFrame.DecodeFailure(
                    declaredType = null,
                    raw = null,
                    diagnostic = boundedDiagnostic("decode_failure: $reason"),
                ),
                raw = emptyRaw,
            )
        }
        val type = (raw["type"] as? JsonPrimitive)?.contentOrNull
        val frame = when (type) {
            "auth_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.AuthResponse>(raw) }
            "runtime_start_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.RuntimeStartResponse>(raw) }
            "sync_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.SyncResponse>(raw) }
            "abort_message_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.AbortMessageResponse>(raw) }
            "stream_delta" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.StreamDelta>(raw) }
            "update_loop_status" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.UpdateLoopStatus>(raw) }
            "update_device_status" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.UpdateDeviceStatus>(raw) }
            "update_queue" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.UpdateQueue>(raw) }
            "update_subagent_state" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.UpdateSubagentState>(raw) }
            "external_tool_call_request" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.ExternalToolCallRequest>(raw) }
            "control_request" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.ControlRequest>(raw) }
            "admin_rpc_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.AdminRpcResponse>(raw) }
            else -> AppServerInboundFrame.Unknown(type = type, raw = raw)
        }
        return AppServerReceivedFrame(channel = channel, frame = frame, raw = raw)
    }

    private inline fun decodeKnown(
        type: String,
        raw: JsonObject,
        decode: () -> AppServerInboundFrame,
    ): AppServerInboundFrame =
        runCatching { decode() }.getOrElse { error ->
            val reason = error.message ?: error::class.simpleName ?: "decode failed"
            AppServerInboundFrame.DecodeFailure(
                declaredType = type,
                raw = raw,
                diagnostic = boundedDiagnostic("decode_failure type=$type: $reason"),
            )
        }

    /**
     * Cap a decode-failure diagnostic to [MAX_DIAGNOSTIC_LENGTH]. Callers compose
     * the message; it must intentionally exclude the raw frame payload (available
     * separately on [AppServerInboundFrame.DecodeFailure.raw]) so nothing that
     * leaves the process — logs, traces, fanout — carries frame contents, and the
     * cap prevents a hostile/oversized frame from bloating sinks.
     */
    fun boundedDiagnostic(message: String): String =
        if (message.length > MAX_DIAGNOSTIC_LENGTH) {
            message.take(MAX_DIAGNOSTIC_LENGTH - 1) + "\u2026"
        } else {
            message
        }

    /**
     * Recursively replace credential-bearing values with [REDACTED_PLACEHOLDER].
     * Field matching mirrors the contract-baseline hygiene predicate so runtime
     * redaction and committed-fixture redaction stay consistent.
     */
    fun redactCredentials(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (isCredentialField(key)) redactedPrimitive else redactCredentials(value)
            },
        )
        is JsonArray -> JsonArray(element.map(::redactCredentials))
        else -> element
    }

    fun isCredentialField(name: String): Boolean {
        val normalized = name.lowercase().filter(Char::isLetterOrDigit)
        return normalized == "authorization" ||
            normalized == "password" ||
            normalized == "privatekey" ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("apikey")
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
        /**
         * Transport capabilities this client supports (e.g. `frame_part` chunked
         * frame reassembly). Absent/null means baseline framing only — servers
         * must never emit capability-gated encodings to such peers.
         */
        val capabilities: List<String>? = null,
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
        /**
         * Transport capabilities the server supports (e.g. `frame_part`).
         * Absent/null means baseline framing only.
         */
        val capabilities: List<String>? = null,
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

    /**
     * A frame that could not be decoded (malformed known frame, non-object frame,
     * or invalid JSON syntax). Surfacing this instead of throwing keeps receive
     * loops alive (letta-mobile-lgns8.4). The raw envelope is preserved when it
     * parsed to an object; [diagnostic] is bounded and credential-redacted and is
     * the only part safe to log/trace/fan out.
     */
    @Serializable
    data class DecodeFailure(
        @SerialName("declared_type") val declaredType: String?,
        val raw: JsonObject? = null,
        val diagnostic: String,
    ) : AppServerInboundFrame {
        @Transient
        override val type: String = DECODE_FAILURE_TYPE

        override val requestId: String?
            get() = (raw?.get("request_id") as? JsonPrimitive)?.contentOrNull

        @Transient
        override val runtime: AppServerRuntimeScope? = null

        companion object {
            const val DECODE_FAILURE_TYPE: String = "decode_failure"
        }
    }
}
