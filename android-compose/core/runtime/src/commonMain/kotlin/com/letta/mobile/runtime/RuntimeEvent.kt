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
}

@Serializable
enum class RuntimeRunStatus {
    Started,
    Running,
    Completed,
    Failed,
    Cancelled,
}
