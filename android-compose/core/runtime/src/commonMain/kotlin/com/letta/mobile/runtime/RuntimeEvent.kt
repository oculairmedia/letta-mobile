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
     * What the agent loop is doing right now, from
     * `update_loop_status.status` (`SENDING_API_REQUEST`, `WAITING_ON_INPUT`, ...). The raw wire
     * token is kept: the upstream union is open, and a newer server may add phases.
     */
    @Serializable
    @SerialName("loop_phase_changed")
    data class LoopPhaseChanged(
        /** Open-ended upstream loop phase token. */
        val status: String,
    ) : RuntimeEventPayload

    /**
     * The server is retrying a provider call (`retry` stream delta).
     * [retryKind], [provider] and [errorCode] arrived in letta-code 0.33 and are null before it.
     */
    @Serializable
    @SerialName("retry_notice")
    data class RetryNotice(
        /** Human-readable status or retry notice, when supplied. */
        val message: String? = null,
        /** Explanation supplied for this failure or lifecycle transition. */
        val reason: String? = null,
        /** Retry attempt reported by the server; zero when omitted. */
        val attempt: Int = 0,
        /** Server-reported retry limit; zero when omitted. */
        val maxAttempts: Int = 0,
        /** Delay before retry in milliseconds. */
        val delayMs: Long = 0L,
        /** Upstream retry category, when supplied. */
        val retryKind: String? = null,
        /** Model provider involved in the retry, when supplied. */
        val provider: String? = null,
        /** Upstream error identifier, when supplied. */
        val errorCode: String? = null,
    ) : RuntimeEventPayload

    /** An informational `status` stream delta, at its [level]. */
    @Serializable
    @SerialName("status_notice")
    data class StatusNotice(
        /** Human-readable status or retry notice, when supplied. */
        val message: String,
        /** Severity token reported by the server, defaulting to info. */
        val level: String = "info",
    ) : RuntimeEventPayload

    /**
     * A server-side command (`command_start`) or slash command
     * (`slash_command_start`) began. Paired with [CommandFinished] by [commandId].
     */
    @Serializable
    @SerialName("command_started")
    data class CommandStarted(
        /** Correlation identity shared by command start and completion. */
        val commandId: String,
        /** Command input reported by the server. */
        val input: String,
        /** Whether this event belongs to a slash command. */
        val slash: Boolean = false,
    ) : RuntimeEventPayload

    /** `command_end` / `slash_command_end`. */
    @Serializable
    @SerialName("command_finished")
    data class CommandFinished(
        /** Correlation identity shared by command start and completion. */
        val commandId: String,
        /** Command input reported by the server. */
        val input: String,
        /** Command result text. */
        val output: String,
        /** Whether the command completed successfully. */
        val success: Boolean,
        /** Whether the command result should use reduced visual emphasis. */
        val dimOutput: Boolean = false,
        /** Whether output whitespace should be preserved. */
        val preformatted: Boolean = false,
        /** Whether this event belongs to a slash command. */
        val slash: Boolean = false,
    ) : RuntimeEventPayload

    /**
     * Letta-code 0.33's `approval_classification_end`, saying which of
     * the turn's tool calls the server's permission rules allowed or denied on their own and which
     * wait for the person.
     */
    @Serializable
    @SerialName("approval_classified")
    data class ApprovalClassified(
        /** Calls permitted automatically by the server policy. */
        val autoAllowedToolCallIds: List<String> = emptyList(),
        /** Calls rejected automatically by the server policy. */
        val autoDeniedToolCallIds: List<String> = emptyList(),
        /** Calls for which a human answer remains necessary. */
        val userInputToolCallIds: List<String> = emptyList(),
    ) : RuntimeEventPayload

    /**
     * The server began compacting the conversation (`event_message` with
     * `event_type: "compaction"`). letta-code streams this marker only for an automatic compaction;
     * a manual compact reports through the command lifecycle instead.
     */
    @Serializable
    @SerialName("compaction_started")
    data class CompactionStarted(
        /** Server-reported reason for compaction, when supplied. */
        val trigger: String? = null,
    ) : RuntimeEventPayload

    /**
     * The compaction's `summary_message`. [stats] is letta-code's
     * `compaction_stats`, absent on servers that do not report it. In local-backend mode its token
     * counts are chars/4 estimates of the transcript only (system prompt and tools excluded).
     */
    @Serializable
    @SerialName("compaction_finished")
    data class CompactionFinished(
        /** Compacted conversation summary text. */
        val summary: String = "",
        /** Optional measurements reported with the compaction result. */
        val stats: CompactionStats? = null,
    ) : RuntimeEventPayload
}

/** letta-code's `compaction_stats`; every field is optional on the wire. */
@Serializable
data class CompactionStats(
    /** Server-reported reason for compaction, when supplied. */
    val trigger: String? = null,
    /** Reported context token count before compaction. */
    val contextTokensBefore: Long? = null,
    /** Reported context token count after compaction. */
    val contextTokensAfter: Long? = null,
    /** Reported model context-window capacity in tokens. */
    val contextWindow: Long? = null,
    /** Reported message count before compaction. */
    val messagesCountBefore: Int? = null,
    /** Reported message count after compaction. */
    val messagesCountAfter: Int? = null,
)

/**
 * Whether this payload reports status, retry, approval classification or compaction progress
 * rather than advancing the reply/tool-call lifecycle. Compaction notices can contain summary
 * text, but must not be mistaken for a new reply, tool call or turn terminal.
 */
val RuntimeEventPayload.isAdvisory: Boolean
    get() = when (this) {
        is RuntimeEventPayload.LoopPhaseChanged,
        is RuntimeEventPayload.RetryNotice,
        is RuntimeEventPayload.StatusNotice,
        is RuntimeEventPayload.ApprovalClassified,
        is RuntimeEventPayload.CompactionStarted,
        is RuntimeEventPayload.CompactionFinished,
        -> true
        else -> false
    }

/** Lifecycle state reported for a runtime run. */
@Serializable
enum class RuntimeRunStatus {
    /** Run accepted or initialized. */
    Started,
    /** Run is actively executing. */
    Running,
    /** Run completed successfully. */
    Completed,
    /** Run terminated with an error. */
    Failed,
    /** Run was cancelled. */
    Cancelled,
}

/**
 * Payloads intended for live status and command progress rather than durable conversation
 * history. Durable writers use this classification to omit them; the outbox interface does
 * not itself filter arbitrary appended drafts.
 */
val RuntimeEventPayload.isPresentationOnly: Boolean
    get() = isAdvisory || this is RuntimeEventPayload.CommandStarted || this is RuntimeEventPayload.CommandFinished
