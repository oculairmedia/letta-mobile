package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ApprovalResult
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.ToolApprovalDecision
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolApprovalScope
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName

fun WsTimelineEvent.toRuntimeEventDrafts(
    backend: BackendDescriptor,
    fallbackAgentId: AgentId? = null,
    fallbackConversationId: ConversationId? = null,
): List<RuntimeEventDraft> = when (this) {
    is WsTimelineEvent.TurnStarted -> listOf(
        runtimeDraft(
            backend = backend,
            agentId = AgentId(agentId),
            conversationId = ConversationId(conversationId),
            runId = RunId(runId),
            source = RuntimeEventSource.ExternalTransport,
            payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started),
        ),
    )

    is WsTimelineEvent.TurnDone -> listOf(
        runtimeDraft(
            backend = backend,
            agentId = fallbackAgentId,
            conversationId = fallbackConversationId,
            runId = runId.toRunIdOrNull(),
            source = RuntimeEventSource.ExternalTransport,
            payload = RuntimeEventPayload.RunLifecycleChanged(
                status = status.toRuntimeRunStatus(),
                reason = if (lossy) "lossy:$dropCount" else null,
            ),
        ),
    )

    is WsTimelineEvent.StopReason -> listOf(
        runtimeDraft(
            backend = backend,
            agentId = fallbackAgentId,
            conversationId = fallbackConversationId,
            runId = runId.toRunIdOrNull(),
            source = RuntimeEventSource.ExternalTransport,
            payload = RuntimeEventPayload.RunLifecycleChanged(
                status = RuntimeRunStatus.Running,
                reason = stopReason,
            ),
        ),
    )

    is WsTimelineEvent.UsageStatistics -> listOf(
        runtimeDraft(
            backend = backend,
            agentId = fallbackAgentId,
            conversationId = fallbackConversationId,
            runId = runId.toRunIdOrNull(),
            source = RuntimeEventSource.ExternalTransport,
            payload = RuntimeEventPayload.ExternalTransportFrame(
                frameId = "usage-$turnId",
                transportMessageId = runId,
                body = "usage:$totalTokens",
            ),
        ),
    )

    is WsTimelineEvent.Error -> listOf(
        runtimeDraft(
            backend = backend,
            agentId = fallbackAgentId,
            conversationId = fallbackConversationId,
            runId = runId?.toRunIdOrNull(),
            source = RuntimeEventSource.ExternalTransport,
            payload = RuntimeEventPayload.RunLifecycleChanged(
                status = RuntimeRunStatus.Failed,
                reason = "$code: $message",
            ),
        ),
    )

    is WsTimelineEvent.MessageDelta -> message.toRuntimeEventDrafts(
        backend = backend,
        fallbackAgentId = fallbackAgentId,
        fallbackConversationId = fallbackConversationId,
        source = RuntimeEventSource.ExternalTransport,
    )

    is WsTimelineEvent.UserActionOutcome -> listOf(
        runtimeDraft(
            backend = backend,
            agentId = agentId?.let(::AgentId) ?: fallbackAgentId,
            conversationId = conversationId?.let(::ConversationId) ?: fallbackConversationId,
            runId = runId?.toRunIdOrNull(),
            source = RuntimeEventSource.ExternalTransport,
            payload = RuntimeEventPayload.ExternalTransportFrame(
                frameId = frameId,
                transportMessageId = actionId,
                body = reason?.let { "$outcome: $it" } ?: outcome,
            ),
        ),
    )

    is WsTimelineEvent.Disconnected -> emptyList()
}

fun LettaMessage.toRuntimeEventDrafts(
    backend: BackendDescriptor,
    fallbackAgentId: AgentId? = null,
    fallbackConversationId: ConversationId? = null,
    source: RuntimeEventSource = RuntimeEventSource.RemoteLetta,
): List<RuntimeEventDraft> {
    val runId = runId?.toRunIdOrNull()
    fun draft(payload: RuntimeEventPayload): RuntimeEventDraft =
        runtimeDraft(
            backend = backend,
            agentId = fallbackAgentId,
            conversationId = fallbackConversationId,
            runId = runId,
            source = source,
            payload = payload,
        )

    return when (this) {
        is UserMessage -> listOf(
            draft(
                RuntimeEventPayload.RemoteStreamFrame(
                    frameId = id,
                    messageId = id,
                    messageType = messageType,
                    body = content,
                ),
            ),
        )

        is AssistantMessage -> listOf(
            draft(
                RuntimeEventPayload.RemoteStreamFrame(
                    frameId = id,
                    messageId = id,
                    messageType = messageType,
                    body = content,
                ),
            ),
        )

        is ReasoningMessage -> listOf(
            draft(
                RuntimeEventPayload.RemoteStreamFrame(
                    frameId = id,
                    messageId = id,
                    messageType = messageType,
                    body = reasoning,
                ),
            ),
        )

        is SystemMessage -> listOf(
            draft(
                RuntimeEventPayload.RemoteStreamFrame(
                    frameId = id,
                    messageId = id,
                    messageType = messageType,
                    body = content,
                ),
            ),
        )

        is ToolCallMessage -> effectiveToolCalls.map { toolCall ->
            draft(toolCall.toToolCallObservedPayload(messageId = id))
        }

        is ApprovalRequestMessage -> effectiveToolCalls.flatMapIndexed { index, toolCall ->
            val callPayload = draft(toolCall.toToolCallObservedPayload(messageId = id))
            val approvalPayload = if (index == 0) {
                listOf(draft(toApprovalRequestedPayload(toolCall)))
            } else {
                emptyList()
            }
            listOf(callPayload) + approvalPayload
        }.ifEmpty {
            listOf(
                draft(
                    RuntimeEventPayload.RemoteStreamFrame(
                        frameId = id,
                        messageId = id,
                        messageType = messageType,
                        body = "approval_request",
                    ),
                ),
            )
        }

        is ToolReturnMessage -> listOf(
            draft(
                RuntimeEventPayload.ToolReturnObserved(
                    toolCallId = ToolCallId(toolReturn.toolCallId.ifBlank { id }),
                    status = toolReturn.status.toToolExecutionStatus(isErr),
                    body = toolReturn.funcResponse ?: "",
                ),
            ),
        )

        is ApprovalResponseMessage -> approvals.orEmpty().mapNotNull { approval ->
            approval.toApprovalResolvedPayload(approvalRequestId)?.let(::draft)
        }.ifEmpty {
            listOf(
                draft(
                    RuntimeEventPayload.RemoteStreamFrame(
                        frameId = id,
                        messageId = id,
                        messageType = messageType,
                        body = reason ?: "approval_response",
                    ),
                ),
            )
        }

        is ErrorMessage -> listOf(
            draft(
                RuntimeEventPayload.RunLifecycleChanged(
                    status = RuntimeRunStatus.Failed,
                    reason = text,
                ),
            ),
        )

        is StopReason -> listOf(
            draft(
                RuntimeEventPayload.RunLifecycleChanged(
                    status = RuntimeRunStatus.Running,
                    reason = reason,
                ),
            ),
        )

        is UsageStatistics -> listOf(
            draft(
                RuntimeEventPayload.RemoteStreamFrame(
                    frameId = id,
                    messageId = id,
                    messageType = messageType,
                    body = "usage:${totalTokens ?: 0}",
                ),
            ),
        )

        else -> emptyList()
    }
}

private fun runtimeDraft(
    backend: BackendDescriptor,
    agentId: AgentId?,
    conversationId: ConversationId?,
    runId: RunId?,
    source: RuntimeEventSource,
    payload: RuntimeEventPayload,
): RuntimeEventDraft = RuntimeEventDraft(
    backendId = backend.backendId,
    runtimeId = backend.runtimeId,
    agentId = agentId,
    conversationId = conversationId,
    runId = runId,
    source = source,
    payload = payload,
)

private fun ToolCall.toToolCallObservedPayload(messageId: String): RuntimeEventPayload.ToolCallObserved =
    RuntimeEventPayload.ToolCallObserved(
        toolCallId = ToolCallId(effectiveId.ifBlank { messageId }),
        toolName = ToolName(name?.takeIf { it.isNotBlank() } ?: "tool"),
        argumentsJson = arguments,
    )

private fun ApprovalRequestMessage.toApprovalRequestedPayload(
    toolCall: ToolCall,
): RuntimeEventPayload.ApprovalRequested {
    val callId = ToolCallId(toolCall.effectiveId.ifBlank { id })
    val toolName = ToolName(toolCall.name?.takeIf { it.isNotBlank() } ?: "tool")
    return RuntimeEventPayload.ApprovalRequested(
        request = ToolApprovalRequest(
            approvalId = ToolApprovalId(id),
            callId = callId,
            toolName = toolName,
            prompt = "Approve ${toolName.value}?",
            argumentsPreview = toolCall.arguments,
        ),
    )
}

private fun ApprovalResult.toApprovalResolvedPayload(
    approvalRequestId: String?,
): RuntimeEventPayload.ApprovalResolved? {
    val requestId = approvalRequestId?.takeIf { it.isNotBlank() } ?: return null
    val callId = toolCallId?.takeIf { it.isNotBlank() } ?: requestId
    return RuntimeEventPayload.ApprovalResolved(
        decision = ToolApprovalDecision(
            approvalId = ToolApprovalId(requestId),
            callId = ToolCallId(callId),
            decision = when {
                approve == false -> ToolApprovalDecisionValue.Denied
                status.equals("denied", ignoreCase = true) -> ToolApprovalDecisionValue.Denied
                else -> ToolApprovalDecisionValue.Approved
            },
            scope = ToolApprovalScope.Once,
            response = reason ?: toolReturn,
        ),
    )
}

private fun String.toRuntimeRunStatus(): RuntimeRunStatus = when (lowercase()) {
    "completed", "complete", "success", "succeeded" -> RuntimeRunStatus.Completed
    "cancelled", "canceled" -> RuntimeRunStatus.Cancelled
    "failed", "error" -> RuntimeRunStatus.Failed
    "started" -> RuntimeRunStatus.Started
    else -> RuntimeRunStatus.Running
}

private fun String.toToolExecutionStatus(isErr: Boolean?): ToolExecutionStatus = when (lowercase()) {
    "success", "succeeded", "ok", "done" -> ToolExecutionStatus.Succeeded
    "failed", "failure", "error" -> ToolExecutionStatus.Failed
    "cancelled", "canceled" -> ToolExecutionStatus.Cancelled
    "running" -> ToolExecutionStatus.Running
    else -> if (isErr == true) ToolExecutionStatus.Failed else ToolExecutionStatus.Succeeded
}

private fun String.toRunIdOrNull(): RunId? = takeIf { it.isNotBlank() }?.let(::RunId)
