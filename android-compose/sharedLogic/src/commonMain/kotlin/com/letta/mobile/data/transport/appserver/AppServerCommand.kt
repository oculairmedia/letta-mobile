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
    // installed @letta-ai/letta-code 0.29.12 protocol declaration. query/body
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

    @Serializable
    @SerialName("conversation_compact")
    data class ConversationCompact(
        @SerialName("request_id") val requestId: String,
        @SerialName("conversation_id") val conversationId: String,
        val body: JsonObject? = null,
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

    /**
     * lgns8.9: the native owner of an agent memory-block write. Core-memory
     * blocks are MemFS files (`memory/system/<label>.md`), and this command is
     * how the App Server — the local backend's single writer — commits one.
     */
    @Serializable
    @SerialName("write_memory_file")
    data class WriteMemoryFile(
        @SerialName("request_id") val requestId: String,
        @SerialName("agent_id") val agentId: String,
        /** Relative to the agent's memory root; rejected upstream if it escapes. */
        val path: String,
        val content: String,
        val encoding: String? = null,
        @SerialName("commit_message") val commitMessage: String? = null,
    ) : AppServerCommand

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

    /**
     * Reads the FULL per-conversation working-directory map the runtime
     * currently holds (letta-mobile folder-settings #2): every
     * `agent:<id>::conversation:default` / `conversation:<id>` scope key it
     * knows a working directory for, plus the process's boot directory as
     * the fallback for scopes absent from the map. Not scoped to one
     * runtime — this is a process-wide read, matching upstream's
     * `get_cwd_map` (no `runtime` field on the request).
     */
    @Serializable
    @SerialName("get_cwd_map")
    data class GetCwdMap(
        @SerialName("request_id") val requestId: String,
    ) : AppServerCommand

    // Channels host ownership (lgns8.23). The App Server accepts these on a bare
    // socket with NO handshake (empirically proven on the 0.29.12 pin), and the
    // registry is NOT restored at boot under `--listen` — so the controller
    // re-asserts enabled accounts itself. See ChannelRestoreCoordinator.
    //
    // CONTROLLER-INTERNAL: responses carry plugin account config verbatim
    // (Matrix accessToken / syncAccessToken in cleartext). They are never fanned
    // out to Iroh viewers (RuntimeEventFanout drops unscoped non-server-initiated
    // frames) and must never be logged, telemetered, or persisted.

    @Serializable
    @SerialName("channels_list")
    data class ChannelsList(
        @SerialName("request_id") val requestId: String,
    ) : AppServerCommand

    @Serializable
    @SerialName("channel_accounts_list")
    data class ChannelAccountsList(
        @SerialName("request_id") val requestId: String,
        @SerialName("channel_id") val channelId: String,
    ) : AppServerCommand

    /**
     * Starts (or restarts) one channel account. A repeated `channel_start` is a
     * clean stop+start, not a no-op — safe to issue on every reconnect, at the
     * cost of one sync bounce. It also re-wires channel ingress to the ISSUING
     * socket, which is why the restore must re-run after every generation flip.
     */
    @Serializable
    @SerialName("channel_start")
    data class ChannelStart(
        @SerialName("request_id") val requestId: String,
        @SerialName("channel_id") val channelId: String,
        @SerialName("account_id") val accountId: String? = null,
    ) : AppServerCommand

    /**
     * Patches one channel account. Deliberately models ONLY the policy fields —
     * never `config` — so the controller can re-assert `enabled` after a failed
     * start (which upstream persists as `enabled:false`) without ever echoing
     * plugin secrets back over the wire.
     */
    @Serializable
    @SerialName("channel_account_update")
    data class ChannelAccountUpdate(
        @SerialName("request_id") val requestId: String,
        @SerialName("channel_id") val channelId: String,
        @SerialName("account_id") val accountId: String,
        val patch: AppServerChannelAccountPatch,
    ) : AppServerCommand

    /**
     * Capability discovery request (lgns8.24).
     *
     * Sent over the WebSocket after connect to discover server capabilities
     * before using protocol features. The response [AppServerInfoResponse]
     * carries the Letta Code version, numeric protocol version, active backend,
     * and capability flags such as `runtime_start` and `split_channels`.
     *
     * This is the WebSocket alternative to `GET /app-server-info` over HTTP.
     */
    @Serializable
    @SerialName("app_server_info")
    data class AppServerInfo(
        @SerialName("request_id") val requestId: String,
    ) : AppServerCommand
}

