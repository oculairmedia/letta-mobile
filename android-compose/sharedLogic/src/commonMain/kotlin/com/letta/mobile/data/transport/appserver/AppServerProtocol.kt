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
            // lgns8.7/.8 native typed responses: defined + correlated on by
            // client methods but absent from this decode dispatch, so real App
            // Server replies fell through to Unknown and every native op hung
            // (e.g. conversation.create timed out at 15s). Decode them here.
            "list_models_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.ListModelsResponse>(raw) }
            "skill_enable_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.SkillEnableResponse>(raw) }
            "skill_disable_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.SkillDisableResponse>(raw) }
            "cron_list_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.CronListResponse>(raw) }
            "cron_add_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.CronAddResponse>(raw) }
            "cron_get_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.CronGetResponse>(raw) }
            "cron_runs_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.CronRunsResponse>(raw) }
            "cron_trigger_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.CronTriggerResponse>(raw) }
            "cron_update_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.CronUpdateResponse>(raw) }
            "cron_delete_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.CronDeleteResponse>(raw) }
            "cron_delete_all_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.CronDeleteAllResponse>(raw) }
            "get_reflection_settings_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.GetReflectionSettingsResponse>(raw) }
            "set_reflection_settings_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.SetReflectionSettingsResponse>(raw) }
            "agent_list_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.AgentListResponse>(raw) }
            "agent_retrieve_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.AgentRetrieveResponse>(raw) }
            "agent_create_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.AgentCreateResponse>(raw) }
            "agent_update_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.AgentUpdateResponse>(raw) }
            "agent_delete_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.AgentDeleteResponse>(raw) }
            "conversation_list_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.ConversationListResponse>(raw) }
            "conversation_retrieve_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.ConversationRetrieveResponse>(raw) }
            "conversation_create_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.ConversationCreateResponse>(raw) }
            "conversation_update_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.ConversationUpdateResponse>(raw) }
            "conversation_messages_list_response" -> decodeKnown(type, raw) { json.decodeFromJsonElement<AppServerInboundFrame.ConversationMessagesListResponse>(raw) }
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

    // Runtime-native admin commands (lgns8.7), shapes pinned against the
    // installed @letta-ai/letta-code 0.28.8 protocol declaration. query/body
    // stay raw JSON so unknown upstream fields pass through untouched.

    @Serializable
    @SerialName("agent_list")
    data class AgentList(
        @SerialName("request_id") val requestId: String,
        val query: JsonObject? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("agent_retrieve")
    data class AgentRetrieve(
        @SerialName("request_id") val requestId: String,
        @SerialName("agent_id") val agentId: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("agent_create")
    data class AgentCreate(
        @SerialName("request_id") val requestId: String,
        val body: JsonObject,
    ) : AppServerCommand

    @Serializable
    @SerialName("agent_update")
    data class AgentUpdate(
        @SerialName("request_id") val requestId: String,
        @SerialName("agent_id") val agentId: String,
        val body: JsonObject,
    ) : AppServerCommand

    @Serializable
    @SerialName("agent_delete")
    data class AgentDelete(
        @SerialName("request_id") val requestId: String,
        @SerialName("agent_id") val agentId: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("conversation_list")
    data class ConversationList(
        @SerialName("request_id") val requestId: String,
        val query: JsonObject? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("conversation_retrieve")
    data class ConversationRetrieve(
        @SerialName("request_id") val requestId: String,
        @SerialName("conversation_id") val conversationId: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("conversation_create")
    data class ConversationCreate(
        @SerialName("request_id") val requestId: String,
        val body: JsonObject,
    ) : AppServerCommand

    @Serializable
    @SerialName("conversation_update")
    data class ConversationUpdate(
        @SerialName("request_id") val requestId: String,
        @SerialName("conversation_id") val conversationId: String,
        val body: JsonObject,
    ) : AppServerCommand

    @Serializable
    @SerialName("conversation_messages_list")
    data class ConversationMessagesList(
        @SerialName("request_id") val requestId: String,
        @SerialName("conversation_id") val conversationId: String,
        val query: JsonObject? = null,
    ) : AppServerCommand

    // Policy-gated control capabilities (lgns8.8).

    @Serializable
    @SerialName("list_models")
    data class ListModels(
        @SerialName("request_id") val requestId: String,
        val force: Boolean? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("skill_enable")
    data class SkillEnable(
        @SerialName("request_id") val requestId: String,
        @SerialName("skill_path") val skillPath: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("skill_disable")
    data class SkillDisable(
        @SerialName("request_id") val requestId: String,
        val name: String,
    ) : AppServerCommand

    // Native cron scheduling (lgns8.8): replaces the legacy mobile-WS cron
    // path, which retires with the shim in lgns8.11.

    @Serializable
    @SerialName("cron_list")
    data class CronList(
        @SerialName("request_id") val requestId: String,
        @SerialName("agent_id") val agentId: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("cron_add")
    data class CronAdd(
        @SerialName("request_id") val requestId: String,
        @SerialName("agent_id") val agentId: String,
        @SerialName("conversation_id") val conversationId: String? = null,
        val name: String,
        val description: String,
        val cron: String,
        val timezone: String? = null,
        val recurring: Boolean,
        val prompt: String,
        @SerialName("scheduled_for") val scheduledFor: String? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("cron_get")
    data class CronGet(
        @SerialName("request_id") val requestId: String,
        @SerialName("task_id") val taskId: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("cron_runs")
    data class CronRuns(
        @SerialName("request_id") val requestId: String,
        @SerialName("task_id") val taskId: String,
        val limit: Int? = null,
        val offset: Int? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("cron_trigger")
    data class CronTrigger(
        @SerialName("request_id") val requestId: String,
        @SerialName("task_id") val taskId: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("cron_update")
    data class CronUpdate(
        @SerialName("request_id") val requestId: String,
        @SerialName("task_id") val taskId: String,
        val name: String? = null,
        val description: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
        val cron: String? = null,
        val timezone: String? = null,
        val recurring: Boolean? = null,
        val prompt: String? = null,
        @SerialName("scheduled_for") val scheduledFor: String? = null,
    ) : AppServerCommand

    @Serializable
    @SerialName("cron_delete")
    data class CronDelete(
        @SerialName("request_id") val requestId: String,
        @SerialName("task_id") val taskId: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("cron_delete_all")
    data class CronDeleteAll(
        @SerialName("request_id") val requestId: String,
        @SerialName("agent_id") val agentId: String,
    ) : AppServerCommand

    // Reflection settings (lgns8.16): runtime-scoped get/set of the agent's
    // reflection trigger + step_count, carried through the native path.

    @Serializable
    @SerialName("get_reflection_settings")
    data class GetReflectionSettings(
        @SerialName("request_id") val requestId: String,
        val runtime: AppServerRuntimeScope,
    ) : AppServerCommand

    @Serializable
    @SerialName("set_reflection_settings")
    data class SetReflectionSettings(
        @SerialName("request_id") val requestId: String,
        val runtime: AppServerRuntimeScope,
        val settings: JsonObject,
        val scope: String? = null,
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
    @SerialName("list_models_response")
    data class ListModelsResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val entries: JsonArray? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "list_models_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("skill_enable_response")
    data class SkillEnableResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("skill_name") val skillName: String? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "skill_enable_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("skill_disable_response")
    data class SkillDisableResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "skill_disable_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("cron_list_response")
    data class CronListResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val tasks: JsonArray? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "cron_list_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }
    @Serializable
    @SerialName("cron_add_response")
    data class CronAddResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val task: JsonObject? = null,
        val warning: String? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "cron_add_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }
    @Serializable
    @SerialName("cron_get_response")
    data class CronGetResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val found: Boolean = false,
        val task: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "cron_get_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }
    @Serializable
    @SerialName("cron_runs_response")
    data class CronRunsResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val page: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "cron_runs_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }
    @Serializable
    @SerialName("cron_trigger_response")
    data class CronTriggerResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val found: Boolean = false,
        val task: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "cron_trigger_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }
    @Serializable
    @SerialName("cron_update_response")
    data class CronUpdateResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val task: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "cron_update_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }
    @Serializable
    @SerialName("cron_delete_response")
    data class CronDeleteResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val found: Boolean = false,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "cron_delete_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }
    @Serializable
    @SerialName("cron_delete_all_response")
    data class CronDeleteAllResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("agent_id") val agentId: String? = null,
        val deleted: Int = 0,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "cron_delete_all_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }
    @Serializable
    @SerialName("get_reflection_settings_response")
    data class GetReflectionSettingsResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("reflection_settings") val reflectionSettings: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "get_reflection_settings_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("set_reflection_settings_response")
    data class SetReflectionSettingsResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("reflection_settings") val reflectionSettings: JsonObject? = null,
        val scope: String? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "set_reflection_settings_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    // Runtime-native admin responses (lgns8.7); entity payloads stay raw
    // JSON (JsonElement/JsonArray) per the lgns8.4 tolerant-model convention.

    @Serializable
    @SerialName("agent_list_response")
    data class AgentListResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val agents: JsonArray? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "agent_list_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("agent_retrieve_response")
    data class AgentRetrieveResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val agent: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "agent_retrieve_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("agent_create_response")
    data class AgentCreateResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val agent: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "agent_create_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("agent_update_response")
    data class AgentUpdateResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val agent: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "agent_update_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("agent_delete_response")
    data class AgentDeleteResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "agent_delete_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("conversation_list_response")
    data class ConversationListResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val conversations: JsonArray? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "conversation_list_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("conversation_retrieve_response")
    data class ConversationRetrieveResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val conversation: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "conversation_retrieve_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("conversation_create_response")
    data class ConversationCreateResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val conversation: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "conversation_create_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("conversation_update_response")
    data class ConversationUpdateResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val conversation: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "conversation_update_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("conversation_messages_list_response")
    data class ConversationMessagesListResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val messages: JsonArray? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "conversation_messages_list_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
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
