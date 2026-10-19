package com.letta.mobile.runtime

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable

/**
 * Replay state for exactly one backend/runtime pair.
 * Its offset and seen-ID sets must not be reused for another scope.
 */
@Serializable
data class RuntimeProjection(
    /** Backend scope that owns this record. */
    val backendId: BackendId,
    /** Runtime scope within the backend. */
    val runtimeId: RuntimeId,
    /** Last accepted event offset; zero before the first accepted event. */
    val lastOffset: RuntimeEventOffset = RuntimeEventOffset(0),
    /** Identities already accepted, used for replay deduplication. */
    val seenEventIds: Set<RuntimeEventId> = emptySet(),
    /** Foreign-scope event identities rejected by the projector. */
    val ignoredEventIds: Set<RuntimeEventId> = emptySet(),
    /** Locally initiated messages in projection order. */
    val localMessages: ImmutableList<RuntimeLocalMessage> = persistentListOf(),
    /** Accepted transport frames in event order. */
    val frames: ImmutableList<RuntimeFrame> = persistentListOf(),
    /** Latest accumulated tool state keyed by invocation. */
    val toolExecutions: Map<ToolCallId, ToolExecutionProjection> = emptyMap(),
    /** Unanswered approvals keyed by approval identity. */
    val pendingApprovals: Map<ToolApprovalId, ToolApprovalRequest> = emptyMap(),
    /** Recorded decisions keyed by approval identity. */
    val resolvedApprovals: Map<ToolApprovalId, ToolApprovalDecision> = emptyMap(),
    /** Latest observed state for each run. */
    val runStatuses: Map<RunId, RuntimeRunStatus> = emptyMap(),
    /** Memory revision associated with the operation, when supplied. */
    val memFsRevision: MemFsRevision? = null,
    /** Known imported and exported file metadata keyed by file identity. */
    val agentFiles: Map<AgentFileId, AgentFile> = emptyMap(),
    /** Most recently observed snapshot identity. */
    val lastSnapshotId: String? = null,
    /** Message count from the most recent snapshot marker. */
    val lastSnapshotMessageCount: Int? = null,
)

/** Optimistic message and the current delivery result tracked by the projector. */
@Serializable
data class RuntimeLocalMessage(
    /** Client-assigned identity retained across delivery and retry. */
    val localMessageId: String,
    /** User-visible message text. */
    val text: String,
    /** Current local-send delivery state. */
    val delivery: RuntimeLocalDelivery = RuntimeLocalDelivery.Sending,
    /** Server identity associated with the local message. */
    val serverMessageId: String? = null,
    /** Last delivery failure explanation, cleared by retry or success. */
    val failureReason: String? = null,
)

/** Delivery state of a locally initiated message. */
@Serializable
enum class RuntimeLocalDelivery {
    /** Initial send is awaiting confirmation. */
    Sending,
    /** Server delivery has been confirmed. */
    Sent,
    /** Send failed and may be retried. */
    Failed,
    /** A retry was requested for the same local identity. */
    Retrying,
}

/** Retained stream frame with origin and optional upstream message metadata. */
@Serializable
data class RuntimeFrame(
    /** Identity assigned to this transport frame. */
    val frameId: String,
    /** Origin category retained across projection and replay. */
    val source: RuntimeEventSource,
    /** Raw frame or tool-result content retained for consumers. */
    val body: String,
    /** Upstream message identity, when supplied. */
    val messageId: String? = null,
    /** Upstream message type, when supplied. */
    val messageType: String? = null,
)

/** Accumulated call metadata and result; fields remain null until observed. */
@Serializable
data class ToolExecutionProjection(
    /** Tool invocation associated with this record. */
    val callId: ToolCallId,
    /** Tool name, or null until the call metadata arrives. */
    val name: ToolName? = null,
    /** Serialized tool arguments, or null until available. */
    val argumentsJson: String? = null,
    /** Latest observed execution state, or null until reported. */
    val status: ToolExecutionStatus? = null,
    /** Raw frame or tool-result content retained for consumers. */
    val body: String? = null,
    /** Tool failure detail, or null when no failure is recorded. */
    val errorMessage: String? = null,
)

/**
 * Pure event fold: foreign-scope IDs are recorded as ignored without advancing the cursor;
 * seen IDs and non-increasing offsets leave state unchanged. No I/O or tool execution occurs.
 */
object RuntimeEventProjector {
    /**
     * Apply a matching unseen event after the cursor. Tool returns may populate a call projection
     * before the corresponding call metadata arrives.
     */
    fun reduce(
        projection: RuntimeProjection,
        event: RuntimeEventEnvelope,
    ): RuntimeProjection {
        if (event.backendId != projection.backendId || event.runtimeId != projection.runtimeId) {
            return projection.copy(ignoredEventIds = projection.ignoredEventIds + event.eventId)
        }
        if (event.eventId in projection.seenEventIds || event.offset <= projection.lastOffset) {
            return projection
        }

        val next = projection.copy(
            lastOffset = event.offset,
            seenEventIds = projection.seenEventIds + event.eventId,
        )

        return when (val payload = event.payload) {
            is RuntimeEventPayload.LocalUserAppend -> next.copy(
                localMessages = (next.localMessages + RuntimeLocalMessage(
                    localMessageId = payload.localMessageId,
                    text = payload.text,
                )).toPersistentList(),
            )

            is RuntimeEventPayload.RemoteStreamFrame -> next.copy(
                frames = (next.frames + RuntimeFrame(
                    frameId = payload.frameId,
                    source = event.source,
                    body = payload.body,
                    messageId = payload.messageId,
                    messageType = payload.messageType,
                )).toPersistentList(),
            )

            is RuntimeEventPayload.ExternalTransportFrame -> next.copy(
                frames = (next.frames + RuntimeFrame(
                    frameId = payload.frameId,
                    source = event.source,
                    body = payload.body,
                    messageId = payload.transportMessageId,
                )).toPersistentList(),
            )

            is RuntimeEventPayload.RestSnapshotReconcile -> next.copy(
                lastSnapshotId = payload.snapshotId,
                lastSnapshotMessageCount = payload.messageCount,
            )

            is RuntimeEventPayload.SendMarkedSent -> next.copy(
                localMessages = next.localMessages.updateLocal(payload.localMessageId) {
                    it.copy(
                        delivery = RuntimeLocalDelivery.Sent,
                        serverMessageId = payload.serverMessageId,
                        failureReason = null,
                    )
                },
            )

            is RuntimeEventPayload.SendMarkedFailed -> next.copy(
                localMessages = next.localMessages.updateLocal(payload.localMessageId) {
                    it.copy(
                        delivery = RuntimeLocalDelivery.Failed,
                        failureReason = payload.reason,
                    )
                },
            )

            is RuntimeEventPayload.RetryRequested -> next.copy(
                localMessages = next.localMessages.updateLocal(payload.localMessageId) {
                    it.copy(
                        delivery = RuntimeLocalDelivery.Retrying,
                        failureReason = null,
                    )
                },
            )

            is RuntimeEventPayload.ToolCallObserved -> next.copy(
                toolExecutions = next.toolExecutions +
                    (payload.toolCallId to next.toolExecutions[payload.toolCallId].mergeCall(payload)),
            )

            is RuntimeEventPayload.ToolReturnObserved -> next.copy(
                toolExecutions = next.toolExecutions +
                    (payload.toolCallId to next.toolExecutions[payload.toolCallId].mergeReturn(payload)),
            )

            is RuntimeEventPayload.ApprovalRequested -> next.copy(
                pendingApprovals = next.pendingApprovals + (payload.request.approvalId to payload.request),
            )

            is RuntimeEventPayload.ApprovalResolved -> next.copy(
                pendingApprovals = next.pendingApprovals - payload.decision.approvalId,
                resolvedApprovals = next.resolvedApprovals + (payload.decision.approvalId to payload.decision),
            )

            is RuntimeEventPayload.RunLifecycleChanged -> event.runId?.let { runId ->
                next.copy(runStatuses = next.runStatuses + (runId to payload.status))
            } ?: next

            is RuntimeEventPayload.MemFsCommitObserved -> next.copy(
                memFsRevision = payload.commit.revision,
            )

            is RuntimeEventPayload.AgentFileImported -> next.copy(
                agentFiles = next.agentFiles + (payload.file.id to payload.file),
            )

            // Exports, and the presentation-only live status and command progress
            // (letta-mobile-bzvro.7/.8/.10), leave no durable record.
            else -> next
        }
    }

    /**
     * Fold [events] in supplied order through [reduce], starting from [seed].
     * Input is not sorted: an earlier offset encountered after a later one is ignored.
     */
    fun replay(
        seed: RuntimeProjection,
        events: Iterable<RuntimeEventEnvelope>,
    ): RuntimeProjection = events.fold(seed, ::reduce)
}

private fun ImmutableList<RuntimeLocalMessage>.updateLocal(
    localMessageId: String,
    transform: (RuntimeLocalMessage) -> RuntimeLocalMessage,
): ImmutableList<RuntimeLocalMessage> =
    map { message ->
        if (message.localMessageId == localMessageId) transform(message) else message
    }.toPersistentList()

private fun ToolExecutionProjection?.mergeCall(
    payload: RuntimeEventPayload.ToolCallObserved,
): ToolExecutionProjection =
    (this ?: ToolExecutionProjection(callId = payload.toolCallId)).copy(
        name = payload.toolName,
        argumentsJson = payload.argumentsJson,
    )

private fun ToolExecutionProjection?.mergeReturn(
    payload: RuntimeEventPayload.ToolReturnObserved,
): ToolExecutionProjection =
    (this ?: ToolExecutionProjection(callId = payload.toolCallId)).copy(
        status = payload.status,
        body = payload.body,
    )
