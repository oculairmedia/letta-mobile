package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val RUNTIME_EVENT_SCHEMA_VERSION: Int = 1

@Serializable
enum class RuntimeEventSource {
    LocalUser,
    RemoteLetta,
    ExternalTransport,
    RestSnapshot,
    LocalRuntime,
    System,
}

@Serializable
data class RuntimeEventEnvelope(
    val offset: RuntimeEventOffset,
    val eventId: RuntimeEventId,
    val backendId: BackendId,
    val runtimeId: RuntimeId,
    val agentId: AgentId? = null,
    val conversationId: ConversationId? = null,
    val runId: RunId? = null,
    val createdAt: EpochMillis,
    val source: RuntimeEventSource,
    val schemaVersion: Int = RUNTIME_EVENT_SCHEMA_VERSION,
    val payload: RuntimeEventPayload,
)

@Serializable
data class RuntimeEventDraft(
    val backendId: BackendId,
    val runtimeId: RuntimeId,
    val agentId: AgentId? = null,
    val conversationId: ConversationId? = null,
    val runId: RunId? = null,
    val source: RuntimeEventSource,
    val payload: RuntimeEventPayload,
)

@Serializable
sealed interface RuntimeEventPayload {
    @Serializable
    @SerialName("local_user_append")
    data class LocalUserAppend(
        val localMessageId: String,
        val text: String,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("remote_stream_frame")
    data class RemoteStreamFrame(
        val frameId: String,
        val messageId: String? = null,
        val messageType: String? = null,
        val body: String,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("external_transport_frame")
    data class ExternalTransportFrame(
        val frameId: String,
        val transportMessageId: String? = null,
        val body: String,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("rest_snapshot_reconcile")
    data class RestSnapshotReconcile(
        val snapshotId: String,
        val messageCount: Int,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("send_marked_sent")
    data class SendMarkedSent(
        val localMessageId: String,
        val serverMessageId: String,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("send_marked_failed")
    data class SendMarkedFailed(
        val localMessageId: String,
        val reason: String,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("retry_requested")
    data class RetryRequested(
        val localMessageId: String,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("tool_call_observed")
    data class ToolCallObserved(
        val toolCallId: ToolCallId,
        val toolName: ToolName,
        val argumentsJson: String? = null,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("tool_return_observed")
    data class ToolReturnObserved(
        val toolCallId: ToolCallId,
        val status: ToolExecutionStatus,
        val body: String,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        val request: ToolApprovalRequest,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        val decision: ToolApprovalDecision,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("run_lifecycle_changed")
    data class RunLifecycleChanged(
        val status: RuntimeRunStatus,
        val reason: String? = null,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("memfs_commit")
    data class MemFsCommitObserved(
        val commit: MemFsCommit,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("agent_file_imported")
    data class AgentFileImported(
        val file: AgentFile,
    ) : RuntimeEventPayload

    @Serializable
    @SerialName("agent_file_exported")
    data class AgentFileExported(
        val file: AgentFile,
    ) : RuntimeEventPayload

    /**
     * letta-mobile-bzvro.7 (F07): what the agent loop is doing right now, from
     * `update_loop_status.status` (`SENDING_API_REQUEST`, `WAITING_ON_INPUT`, ...). The raw wire
     * token is kept: the upstream union is open, and a newer server may add phases.
     */
    @Serializable
    @SerialName("loop_phase_changed")
    data class LoopPhaseChanged(
        val status: String,
    ) : RuntimeEventPayload

    /**
     * letta-mobile-bzvro.7 (F07): the server is retrying a provider call (`retry` stream delta).
     * [retryKind], [provider] and [errorCode] arrived in letta-code 0.33 and are null before it.
     */
    @Serializable
    @SerialName("retry_notice")
    data class RetryNotice(
        val message: String? = null,
        val reason: String? = null,
        val attempt: Int = 0,
        val maxAttempts: Int = 0,
        val delayMs: Long = 0L,
        val retryKind: String? = null,
        val provider: String? = null,
        val errorCode: String? = null,
    ) : RuntimeEventPayload

    /** letta-mobile-bzvro.7 (F07): an informational `status` stream delta, at its [level]. */
    @Serializable
    @SerialName("status_notice")
    data class StatusNotice(
        val message: String,
        val level: String = "info",
    ) : RuntimeEventPayload

    /**
     * letta-mobile-bzvro.8 (F08): a server-side command (`command_start`) or slash command
     * (`slash_command_start`) began. Paired with [CommandFinished] by [commandId].
     */
    @Serializable
    @SerialName("command_started")
    data class CommandStarted(
        val commandId: String,
        val input: String,
        val slash: Boolean = false,
    ) : RuntimeEventPayload

    /** letta-mobile-bzvro.8 (F08): `command_end` / `slash_command_end`. */
    @Serializable
    @SerialName("command_finished")
    data class CommandFinished(
        val commandId: String,
        val input: String,
        val output: String,
        val success: Boolean,
        val dimOutput: Boolean = false,
        val preformatted: Boolean = false,
        val slash: Boolean = false,
    ) : RuntimeEventPayload

    /**
     * letta-mobile-bzvro.10 (F10): letta-code 0.33's `approval_classification_end`, saying which of
     * the turn's tool calls the server's permission rules allowed or denied on their own and which
     * wait for the person.
     */
    @Serializable
    @SerialName("approval_classified")
    data class ApprovalClassified(
        val autoAllowedToolCallIds: List<String> = emptyList(),
        val autoDeniedToolCallIds: List<String> = emptyList(),
        val userInputToolCallIds: List<String> = emptyList(),
    ) : RuntimeEventPayload
}

/**
 * letta-mobile-bzvro.7: payloads that describe the run without being part of it. They never carry
 * content, a tool call or a terminal, so turn bookkeeping (round tails, terminal settling) must not
 * treat them as the turn moving on.
 */
val RuntimeEventPayload.isAdvisory: Boolean
    get() = when (this) {
        is RuntimeEventPayload.LoopPhaseChanged,
        is RuntimeEventPayload.RetryNotice,
        is RuntimeEventPayload.StatusNotice,
        is RuntimeEventPayload.ApprovalClassified,
        -> true
        else -> false
    }

@Serializable
enum class RuntimeRunStatus {
    Started,
    Running,
    Completed,
    Failed,
    Cancelled,
}

/**
 * letta-mobile-bzvro.7/.8: payloads that only feed the live status line (advisory events and
 * command progress). Durable runtime logs and outboxes skip them.
 */
val RuntimeEventPayload.isPresentationOnly: Boolean
    get() = isAdvisory || this is RuntimeEventPayload.CommandStarted || this is RuntimeEventPayload.CommandFinished
