package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema version emitted by newly constructed [RuntimeEventEnvelope] values.
 */
const val RUNTIME_EVENT_SCHEMA_VERSION: Int = 1

/** Origin category retained when an event is projected or replayed. */
@Serializable
enum class RuntimeEventSource {
    /** Locally initiated user input. */
    LocalUser,
    /** Event originating from a remote Letta backend. */
    RemoteLetta,
    /** Event received through an external transport. */
    ExternalTransport,
    /** Snapshot reconciliation marker. */
    RestSnapshot,
    /** Event emitted by local execution. */
    LocalRuntime,
    /** Client or runtime system bookkeeping. */
    System,
}

/**
 * Recorded event with storage identity, exclusive resume offset, provenance and scope.
 * [offset] orders the outbox, not a remote server stream; [eventId] supports replay deduplication.
 * Nullable agent/conversation/run IDs permit runtime-wide events. [schemaVersion] is persisted
 * so readers can distinguish future schema migrations.
 */
@Serializable
data class RuntimeEventEnvelope(
    /** Monotonic outbox position, used as an exclusive resume cursor. */
    val offset: RuntimeEventOffset,
    /** Identity used to reject replayed events. */
    val eventId: RuntimeEventId,
    /** Backend scope that owns this record. */
    val backendId: BackendId,
    /** Runtime scope within the backend. */
    val runtimeId: RuntimeId,
    /** Agent associated with the record, when available. */
    val agentId: AgentId? = null,
    /** Conversation associated with the record, when available. */
    val conversationId: ConversationId? = null,
    /** Run associated with the event, when available. */
    val runId: RunId? = null,
    /** Creation time in Unix-epoch milliseconds; not an ordering cursor. */
    val createdAt: EpochMillis,
    /** Origin category retained across projection and replay. */
    val source: RuntimeEventSource,
    /** Envelope schema used to decode this record. */
    val schemaVersion: Int = RUNTIME_EVENT_SCHEMA_VERSION,
    /** Typed content or lifecycle change carried by the event. */
    val payload: RuntimeEventPayload,
)

/**
 * Unsequenced adapter/engine output. [RuntimeEventOutbox] assigns the envelope’s identity,
 * offset and recorded time when appended.
 */
@Serializable
data class RuntimeEventDraft(
    /** Backend scope that owns this record. */
    val backendId: BackendId,
    /** Runtime scope within the backend. */
    val runtimeId: RuntimeId,
    /** Agent associated with the record, when available. */
    val agentId: AgentId? = null,
    /** Conversation associated with the record, when available. */
    val conversationId: ConversationId? = null,
    /** Run associated with the event, when available. */
    val runId: RunId? = null,
    /** Origin category retained across projection and replay. */
    val source: RuntimeEventSource,
    /** Typed content or lifecycle change carried by the event. */
    val payload: RuntimeEventPayload,
)

/**
 * Serializable content/lifecycle vocabulary consumed by [RuntimeEventProjector].
 * Frame bodies remain opaque to the outbox. [isPresentationOnly] marks live status/progress
 * that durable writers may omit.
 */
@Serializable
sealed interface RuntimeEventPayload {
    /** Optimistic local message inserted before server delivery is confirmed. */
    @Serializable
    @SerialName("local_user_append")
    data class LocalUserAppend(
        /** Client-assigned identity retained across delivery and retry. */
        val localMessageId: String,
        /** User-visible message text. */
        val text: String,
    ) : RuntimeEventPayload

    /** Raw upstream message frame with optional message identity and type. */
    @Serializable
    @SerialName("remote_stream_frame")
    data class RemoteStreamFrame(
        /** Identity assigned to this transport frame. */
        val frameId: String,
        /** Upstream message identity, when supplied. */
        val messageId: String? = null,
        /** Upstream message type, when supplied. */
        val messageType: String? = null,
        /** Raw frame or tool-result content retained for consumers. */
        val body: String,
    ) : RuntimeEventPayload

    /** Raw external transport frame, preserving its transport message identity. */
    @Serializable
    @SerialName("external_transport_frame")
    data class ExternalTransportFrame(
        /** Identity assigned to this transport frame. */
        val frameId: String,
        /** Upstream message identity supplied by the transport. */
        val transportMessageId: String? = null,
        /** Raw frame or tool-result content retained for consumers. */
        val body: String,
    ) : RuntimeEventPayload

    /** Marker for the latest reconciled snapshot; does not itself contain messages. */
    @Serializable
    @SerialName("rest_snapshot_reconcile")
    data class RestSnapshotReconcile(
        /** Identity of the reconciled snapshot. */
        val snapshotId: String,
        /** Number of messages reported in that snapshot. */
        val messageCount: Int,
    ) : RuntimeEventPayload

    /** Associates an optimistic local message with its confirmed server identity. */
    @Serializable
    @SerialName("send_marked_sent")
    data class SendMarkedSent(
        /** Client-assigned identity retained across delivery and retry. */
        val localMessageId: String,
        /** Server identity associated with the local message. */
        val serverMessageId: String,
    ) : RuntimeEventPayload

    /** Marks a local send as failed while retaining its retry identity. */
    @Serializable
    @SerialName("send_marked_failed")
    data class SendMarkedFailed(
        /** Client-assigned identity retained across delivery and retry. */
        val localMessageId: String,
        /** Explanation supplied for this failure or lifecycle transition. */
        val reason: String,
    ) : RuntimeEventPayload

    /** Returns an existing local message to the retrying delivery state. */
    @Serializable
    @SerialName("retry_requested")
    data class RetryRequested(
        /** Client-assigned identity retained across delivery and retry. */
        val localMessageId: String,
    ) : RuntimeEventPayload

    /** Metadata for one observed tool invocation, potentially arriving after its result. */
    @Serializable
    @SerialName("tool_call_observed")
    data class ToolCallObserved(
        /** Invocation identity used to correlate call and return events. */
        val toolCallId: ToolCallId,
        /** Model-facing name of the invoked tool. */
        val toolName: ToolName,
        /** Serialized tool arguments, or null until available. */
        val argumentsJson: String? = null,
    ) : RuntimeEventPayload

    /** Result body and execution outcome correlated to a tool invocation. */
    @Serializable
    @SerialName("tool_return_observed")
    data class ToolReturnObserved(
        /** Invocation identity used to correlate call and return events. */
        val toolCallId: ToolCallId,
        /** Execution outcome reported for this invocation. */
        val status: ToolExecutionStatus,
        /** Raw frame or tool-result content retained for consumers. */
        val body: String,
    ) : RuntimeEventPayload

    /** Adds a pending human decision to the runtime projection. */
    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        /** Approval details to present for a human decision. */
        val request: ToolApprovalRequest,
    ) : RuntimeEventPayload

    /** Records a human decision and removes its pending approval. */
    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        /** Decision completing the pending approval. */
        val decision: ToolApprovalDecision,
    ) : RuntimeEventPayload

    /** Updates the lifecycle of the run identified by the enclosing event. */
    @Serializable
    @SerialName("run_lifecycle_changed")
    data class RunLifecycleChanged(
        /** New lifecycle state for the enclosing run. */
        val status: RuntimeRunStatus,
        /** Explanation supplied for this failure or lifecycle transition. */
        val reason: String? = null,
    ) : RuntimeEventPayload

    /** Reports the memory revision reached by a virtual-file change. */
    @Serializable
    @SerialName("memfs_commit")
    data class MemFsCommitObserved(
        /** Virtual-file change whose revision was observed. */
        val commit: MemFsCommit,
    ) : RuntimeEventPayload

    /** Adds imported file metadata to the runtime file index. */
    @Serializable
    @SerialName("agent_file_imported")
    data class AgentFileImported(
        /** Metadata describing the imported or exported file. */
        val file: AgentFile,
    ) : RuntimeEventPayload

    /** Reports exported file metadata without embedding file content. */
    @Serializable
    @SerialName("agent_file_exported")
    data class AgentFileExported(
        /** Metadata describing the imported or exported file. */
        val file: AgentFile,
    ) : RuntimeEventPayload

    /**
