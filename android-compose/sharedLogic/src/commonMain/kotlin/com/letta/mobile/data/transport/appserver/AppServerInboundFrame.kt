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
@JsonClassDiscriminator("type")
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

    /**
     * Response to `runtime_start`.
     *
     * Note on skills: `runtime_start.skill_sources` is a **request-only** field.
     * Upstream 0.29.12 validates it on the inbound command
     * (`isRuntimeStartCommand`) and stores it per conversation in
     * `applyRuntimeStartState`; `sendRuntimeStartResponse` never echoes it back.
     * There is therefore no authoritative skill enumeration to read from this
     * frame — see [com.letta.mobile.data.controller.node.iroh.NativeSkillsCatalog].
     */
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

    /**
     * Response to `skill_enable`.
     *
     * Verified against `@letta-ai/letta-code` 0.29.12 (`letta.js`,
     * `// src/websocket/listener/commands/skills-agents.ts`): a successful enable
     * sends `{ type, request_id, success: true, name, skill_path, link_path }`.
     * Upstream sends `name` — never `skill_name`. These three fields are the only
     * authoritative skill facts the App Server puts on the wire.
     */
    @Serializable
    @SerialName("skill_enable_response")
    data class SkillEnableResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("name") val skillName: String? = null,
        @SerialName("skill_path") val skillPath: String? = null,
        @SerialName("link_path") val linkPath: String? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "skill_enable_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    /**
     * Response to `skill_disable`. Upstream 0.29.12 sends
     * `{ type, request_id, success, name }` on success, `{ ..., error }` otherwise.
     */
    @Serializable
    @SerialName("skill_disable_response")
    data class SkillDisableResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("name") val skillName: String? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "skill_disable_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    /**
     * Skill-set invalidation signal.
     *
     * Upstream 0.29.12 `emitSkillsUpdated` sends exactly `{ type, timestamp }` —
     * it carries **no** skill array. Modelling a `skills` payload here previously
     * let fixture tests pass against a shape upstream never sends; consumers must
     * treat this frame as "re-read your catalog", never as a snapshot.
     *
     * Envelope fields ([runtime], [eventSeq], [emittedAt], [idempotencyKey]) are
     * optional because the wrapper's own fanout stamps them on replayed frames.
     */
    @Serializable
    @SerialName("skills_updated")
    data class SkillsUpdated(
        override val runtime: AppServerRuntimeScope? = null,
        val timestamp: Long? = null,
        @SerialName("event_seq") val eventSeq: Long? = null,
        @SerialName("emitted_at") val emittedAt: String? = null,
        @SerialName("idempotency_key") val idempotencyKey: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "skills_updated"

        @Transient override val requestId: String? = null
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
    @SerialName("write_memory_file_response")
    data class WriteMemoryFileResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("agent_id") val agentId: String? = null,
        val path: String? = null,
        val committed: Boolean = false,
        @SerialName("commit_sha") val commitSha: String? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "write_memory_file_response"

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

    /**
     * Response to [AppServerCommand.GetCwdMap]. [cwdMap] keys are scope keys
     * ("conversation:<id>" or "agent:<id>::conversation:default" — see
     * [WorkingDirectoryScopeKey]); values are absolute paths. A scope key
     * absent from [cwdMap] hasn't been customized and falls back to
     * [bootWorkingDirectory].
     */
    @Serializable
    @SerialName("get_cwd_map_response")
    data class GetCwdMapResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("cwd_map") val cwdMap: Map<String, String> = emptyMap(),
        @SerialName("boot_working_directory") val bootWorkingDirectory: String? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "get_cwd_map_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    // Channels host ownership (lgns8.23). CONTROLLER-INTERNAL — see the command
    // block for the credential-handling contract.

    @Serializable
    @SerialName("channels_list_response")
    data class ChannelsListResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val channels: List<AppServerChannelSummary> = emptyList(),
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "channels_list_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("channel_accounts_list_response")
    data class ChannelAccountsListResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("channel_id") val channelId: String? = null,
        val accounts: List<AppServerChannelAccount> = emptyList(),
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "channel_accounts_list_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("channel_start_response")
    data class ChannelStartResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val channel: AppServerChannelSummary? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "channel_start_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    @Serializable
    @SerialName("channel_account_update_response")
    data class ChannelAccountUpdateResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("channel_id") val channelId: String? = null,
        val account: AppServerChannelAccount? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "channel_account_update_response"

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
    @SerialName("conversation_compact_response")
    data class ConversationCompactResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        val compaction: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "conversation_compact_response"

        @Transient override val runtime: AppServerRuntimeScope? = null
    }

    /**
     * Response to `app_server_info` capability discovery request (lgns8.24).
     *
     * Upstream carries server version, protocol version, backend, and capability
     * flags as top-level fields. [info] reconstructs the typed descriptor callers
     * inspect before using protocol features that depend on those capabilities.
     */
    @Serializable
    @SerialName("app_server_info_response")
    data class AppServerInfoResponse(
        @SerialName("request_id") override val requestId: String,
        val success: Boolean,
        @SerialName("letta_code_version") val lettaCodeVersion: String? = null,
        @SerialName("protocol_version") val protocolVersion: Int? = null,
        val backend: String? = null,
        val capabilities: JsonObject? = null,
        val error: String? = null,
    ) : AppServerInboundFrame {
        @Transient override val type: String = "app_server_info_response"

        @Transient override val runtime: AppServerRuntimeScope? = null

        /** Canonical capability descriptor reconstructed from the wire's top-level fields. */
        val info: AppServerInfoData?
            get() = if (
                lettaCodeVersion != null || protocolVersion != null || backend != null || capabilities != null
            ) {
                AppServerInfoData(
                    lettaCodeVersion = lettaCodeVersion,
                    protocolVersion = protocolVersion,
                    backend = backend,
                    capabilities = capabilities,
                )
            } else {
                null
            }
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
