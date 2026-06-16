package com.letta.mobile.runtime

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeProjection(
    val backendId: BackendId,
    val runtimeId: RuntimeId,
    val lastOffset: RuntimeEventOffset = RuntimeEventOffset(0),
    val seenEventIds: Set<RuntimeEventId> = emptySet(),
    val ignoredEventIds: Set<RuntimeEventId> = emptySet(),
    val localMessages: List<RuntimeLocalMessage> = emptyList(),
    val frames: List<RuntimeFrame> = emptyList(),
    val toolExecutions: Map<ToolCallId, ToolExecutionProjection> = emptyMap(),
    val pendingApprovals: Map<ToolApprovalId, ToolApprovalRequest> = emptyMap(),
    val resolvedApprovals: Map<ToolApprovalId, ToolApprovalDecision> = emptyMap(),
    val runStatuses: Map<RunId, RuntimeRunStatus> = emptyMap(),
    val memFsRevision: MemFsRevision? = null,
    val agentFiles: Map<AgentFileId, AgentFile> = emptyMap(),
    val lastSnapshotId: String? = null,
    val lastSnapshotMessageCount: Int? = null,
)

@Serializable
data class RuntimeLocalMessage(
    val localMessageId: String,
    val text: String,
    val delivery: RuntimeLocalDelivery = RuntimeLocalDelivery.Sending,
    val serverMessageId: String? = null,
    val failureReason: String? = null,
)

@Serializable
enum class RuntimeLocalDelivery {
    Sending,
    Sent,
    Failed,
    Retrying,
}

@Serializable
data class RuntimeFrame(
    val frameId: String,
    val source: RuntimeEventSource,
    val body: String,
    val messageId: String? = null,
    val messageType: String? = null,
)

@Serializable
data class ToolExecutionProjection(
    val callId: ToolCallId,
    val name: ToolName? = null,
    val argumentsJson: String? = null,
    val status: ToolExecutionStatus? = null,
    val body: String? = null,
    val errorMessage: String? = null,
)

object RuntimeEventProjector {
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
                localMessages = next.localMessages + RuntimeLocalMessage(
                    localMessageId = payload.localMessageId,
                    text = payload.text,
                ),
            )

            is RuntimeEventPayload.RemoteStreamFrame -> next.copy(
                frames = next.frames + RuntimeFrame(
                    frameId = payload.frameId,
                    source = event.source,
                    body = payload.body,
                    messageId = payload.messageId,
                    messageType = payload.messageType,
                ),
            )

            is RuntimeEventPayload.ExternalTransportFrame -> next.copy(
                frames = next.frames + RuntimeFrame(
                    frameId = payload.frameId,
                    source = event.source,
                    body = payload.body,
                    messageId = payload.transportMessageId,
                ),
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

            is RuntimeEventPayload.AgentFileExported -> next
        }
    }

    fun replay(
        seed: RuntimeProjection,
        events: Iterable<RuntimeEventEnvelope>,
    ): RuntimeProjection = events.fold(seed, ::reduce)
}

private fun List<RuntimeLocalMessage>.updateLocal(
    localMessageId: String,
    transform: (RuntimeLocalMessage) -> RuntimeLocalMessage,
): List<RuntimeLocalMessage> =
    map { message ->
        if (message.localMessageId == localMessageId) transform(message) else message
    }

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
