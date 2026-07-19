package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ApprovalDecisionPayload
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalRequestPayload
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ApprovalResponsePayload
import com.letta.mobile.data.model.ApprovalToolCallPayload
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import java.time.Instant

internal data class ToolCallContext(
    val name: String,
    val arguments: String,
)

class MessageMappingState internal constructor(
    internal val toolCallsById: MutableMap<String, ToolCallContext> = mutableMapOf(),
)

internal fun List<LettaMessage>.mapToAppMessages(): List<AppMessage> {
    val state = MessageMappingState()
    return mapNotNull { it.mapToAppMessage(state) }
}

internal fun LettaMessage.mapToAppMessage(state: MessageMappingState): AppMessage? {
    return when (this) {
        is UserMessage -> AppMessage(
            id = id,
            date = date.toInstantOrNow(),
            messageType = MessageType.USER,
            content = content,
            runId = runId,
            stepId = stepId,
            attachments = attachments,
        )
        is AssistantMessage -> {
            val generatedUi = extractGeneratedUi(contentRaw)
            AppMessage(
                id = id,
                date = date.toInstantOrNow(),
                messageType = MessageType.ASSISTANT,
                content = generatedUi?.fallbackText.orEmpty().ifBlank {
                    if (generatedUi != null) "" else content
                },
                runId = runId,
                stepId = stepId,
                generatedUi = generatedUi,
                attachments = attachments,
            )
        }
        is ReasoningMessage -> AppMessage(
            id = id,
            date = date.toInstantOrNow(),
            messageType = MessageType.REASONING,
            content = reasoning,
            runId = runId,
            stepId = stepId,
        )
        is ToolCallMessage -> mapToolCall(state)
        is ApprovalRequestMessage -> mapApprovalRequest(state)
        is ToolReturnMessage -> mapToolReturn(state)
        is ApprovalResponseMessage -> AppMessage(
            id = id,
            date = date.toInstantOrNow(),
            messageType = MessageType.APPROVAL_RESPONSE,
            content = "",
            runId = runId,
            stepId = stepId,
            approvalResponse = ApprovalResponsePayload(
                requestId = approvalRequestId,
                approved = approve,
                reason = reason,
                approvals = approvals.orEmpty().mapNotNull { approval ->
                    val callId = approval.toolCallId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ApprovalDecisionPayload(callId, approval.approve, approval.status, approval.reason)
                },
            ),
        )
        else -> null
    }
}

private fun ToolCallMessage.mapToolCall(state: MessageMappingState): AppMessage {
    val toolCall = effectiveToolCalls.firstOrNull()
    val callId = toolCall?.effectiveId
    val toolName = toolCall?.name
    val arguments = toolCall?.arguments.orEmpty()
    state.remember(callId, toolName, arguments)
    return AppMessage(
        id = id,
        date = date.toInstantOrNow(),
        messageType = MessageType.TOOL_CALL,
        content = arguments,
        runId = runId,
        stepId = stepId,
        toolName = toolName,
        toolCallId = callId,
    )
}

private fun ApprovalRequestMessage.mapApprovalRequest(state: MessageMappingState): AppMessage {
    val calls = effectiveToolCalls.mapNotNull { toolCall ->
        val callId = toolCall.effectiveId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val toolName = toolCall.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val arguments = toolCall.arguments.orEmpty()
        state.remember(callId, toolName, arguments)
        ApprovalToolCallPayload(callId, toolName, arguments)
    }
    return AppMessage(
        id = id,
        date = date.toInstantOrNow(),
        messageType = MessageType.APPROVAL_REQUEST,
        content = "",
        runId = runId,
        stepId = stepId,
        approvalRequest = ApprovalRequestPayload(id, calls),
    )
}

private fun ToolReturnMessage.mapToolReturn(state: MessageMappingState): AppMessage {
    val callId = toolReturn.toolCallId
    return AppMessage(
        id = id,
        date = date.toInstantOrNow(),
        messageType = MessageType.TOOL_RETURN,
        content = toolReturn.funcResponse ?: "",
        runId = runId,
        stepId = stepId,
        toolName = state.toolCallsById[callId]?.name ?: name,
        toolCallId = callId,
        toolReturnStatus = toolReturn.status,
        attachments = attachments,
    )
}

private fun MessageMappingState.remember(callId: String?, name: String?, arguments: String) {
    if (!callId.isNullOrBlank() && !name.isNullOrBlank()) {
        toolCallsById[callId] = ToolCallContext(name, arguments)
    }
}

private fun String?.toInstantOrNow(): Instant =
    try {
        this?.let(Instant::parse) ?: Instant.now()
    } catch (_: Exception) {
        Instant.now()
    }
