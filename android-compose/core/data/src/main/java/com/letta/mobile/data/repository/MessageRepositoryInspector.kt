package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.EventMessage
import com.letta.mobile.data.model.HiddenReasoningMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.PingMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UnknownMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import kotlinx.serialization.json.JsonPrimitive

private data class InspectorEnvelope(
    val id: String,
    val messageType: String,
    val date: String?,
    val runId: String?,
    val stepId: String?,
    val otid: String?,
    val baseDetails: List<Pair<String, String>>,
) {
    fun toMessage(summary: String, extraDetails: List<Pair<String, String>> = emptyList()): ConversationInspectorMessage =
        ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = summary,
            detailLines = baseDetails + extraDetails,
        )
}

private fun LettaMessage.inspectorEnvelope(): InspectorEnvelope {
    val baseDetails = buildList {
        date?.let { add("Date" to it) }
        runId?.let { add("Run ID" to it) }
        stepId?.let { add("Step ID" to it) }
        otid?.let { add("OTID" to it) }
    }
    return InspectorEnvelope(
        id = id,
        messageType = messageType,
        date = date,
        runId = runId,
        stepId = stepId,
        otid = otid,
        baseDetails = baseDetails,
    )
}

private fun String?.senderDetail(): List<Pair<String, String>> =
    this?.let { listOf("Sender ID" to it) }.orEmpty()

internal fun LettaMessage.toInspectorMessage(): ConversationInspectorMessage {
    val envelope = inspectorEnvelope()
    return when (this) {
        is UserMessage -> envelope.toMessage(
            summary = content.ifBlank { "User message" },
            extraDetails = senderId.senderDetail(),
        )
        is AssistantMessage -> envelope.toMessage(
            summary = content.ifBlank { "Assistant message" },
            extraDetails = senderId.senderDetail(),
        )
        is ReasoningMessage -> envelope.toMessage(
            summary = reasoning,
            extraDetails = senderId.senderDetail(),
        )
        is ToolCallMessage -> toToolCallInspector(envelope)
        is ToolReturnMessage -> toToolReturnInspector(envelope)
        is ApprovalRequestMessage -> toApprovalRequestInspector(envelope)
        is ApprovalResponseMessage -> toApprovalResponseInspector(envelope)
        is HiddenReasoningMessage -> toHiddenReasoningInspector(envelope)
        is EventMessage -> toEventInspector(envelope)
        is SystemMessage -> envelope.toMessage(
            summary = content.ifBlank { "System message" },
            extraDetails = senderId.senderDetail(),
        )
        is PingMessage -> envelope.toMessage(summary = "Ping")
        is UnknownMessage -> envelope.toMessage(summary = "Unknown message type")
        is ErrorMessage -> toErrorInspector(envelope)
        is StopReason -> envelope.toMessage(
            summary = "Stop: $reason",
            extraDetails = listOf("Reason" to reason),
        )
        is UsageStatistics -> toUsageInspector(envelope)
    }
}

private fun ToolCallMessage.toToolCallInspector(envelope: InspectorEnvelope): ConversationInspectorMessage {
    val firstCall = effectiveToolCalls.firstOrNull()
    return envelope.toMessage(
        summary = firstCall?.name ?: "Tool call",
        extraDetails = listOf(
            "Tool Call ID" to (firstCall?.effectiveId ?: ""),
            "Arguments" to (firstCall?.arguments ?: ""),
        ) + senderId.senderDetail(),
    )
}

private fun ToolReturnMessage.toToolReturnInspector(envelope: InspectorEnvelope): ConversationInspectorMessage =
    envelope.toMessage(
        summary = toolReturn.funcResponse ?: toolReturn.status,
        extraDetails = buildList {
            add("Tool Call ID" to toolReturn.toolCallId)
            add("Status" to toolReturn.status)
            toolReturn.funcResponse?.let { add("Function Response" to it) }
            toolReturn.stdout?.takeIf { it.isNotEmpty() }?.let { add("Stdout" to it.joinToString("\n")) }
            toolReturn.stderr?.takeIf { it.isNotEmpty() }?.let { add("Stderr" to it.joinToString("\n")) }
            senderId?.let { add("Sender ID" to it) }
        },
    )

private fun ApprovalRequestMessage.toApprovalRequestInspector(
    envelope: InspectorEnvelope,
): ConversationInspectorMessage =
    envelope.toMessage(
        summary = "Approval request",
        extraDetails = buildList {
            add("Tool Call Count" to effectiveToolCalls.size.toString())
            effectiveToolCalls.forEachIndexed { index, toolCall ->
                add("Tool ${index + 1}" to "${toolCall.name}: ${toolCall.arguments}")
            }
            senderId?.let { add("Sender ID" to it) }
        },
    )

private fun ApprovalResponseMessage.toApprovalResponseInspector(
    envelope: InspectorEnvelope,
): ConversationInspectorMessage =
    envelope.toMessage(
        summary = "Approval response",
        extraDetails = buildList {
            add("Approval Count" to (approvals?.size ?: 0).toString())
            approve?.let { add("Approved" to it.toString()) }
            approvalRequestId?.let { add("Approval Request ID" to it) }
            reason?.let { add("Reason" to it) }
            approvals?.forEachIndexed { index, approval ->
                add(
                    "Approval ${index + 1}" to listOfNotNull(
                        approval.status,
                        approval.type,
                        approval.toolCallId,
                        approval.toolReturn,
                        approval.approve?.toString(),
                        approval.reason,
                    ).joinToString(" • ")
                )
            }
            senderId?.let { add("Sender ID" to it) }
        },
    )

private fun HiddenReasoningMessage.toHiddenReasoningInspector(
    envelope: InspectorEnvelope,
): ConversationInspectorMessage =
    envelope.toMessage(
        summary = hiddenReasoning ?: state,
        extraDetails = buildList {
            add("State" to state)
            hiddenReasoning?.let { add("Hidden Reasoning" to it) }
            senderId?.let { add("Sender ID" to it) }
        },
    )

private fun EventMessage.toEventInspector(envelope: InspectorEnvelope): ConversationInspectorMessage =
    envelope.toMessage(
        summary = eventType,
        extraDetails = buildList {
            add("Event Type" to eventType)
            eventData?.forEach { (key, value) ->
                val rendered = (value as? JsonPrimitive)?.content ?: value.toString()
                add(key to rendered)
            }
            senderId?.let { add("Sender ID" to it) }
        },
    )

private fun ErrorMessage.toErrorInspector(envelope: InspectorEnvelope): ConversationInspectorMessage =
    envelope.toMessage(
        summary = "Error: ${text.take(120)}",
        extraDetails = buildList {
            add("Error Text" to text)
            code?.let { add("Code" to it) }
        },
    )

private fun UsageStatistics.toUsageInspector(envelope: InspectorEnvelope): ConversationInspectorMessage =
    envelope.toMessage(
        summary = "Usage: ${totalTokens ?: 0} tokens",
        extraDetails = buildList {
            promptTokens?.let { add("Prompt Tokens" to it.toString()) }
            completionTokens?.let { add("Completion Tokens" to it.toString()) }
            totalTokens?.let { add("Total Tokens" to it.toString()) }
            stepCount?.let { add("Step Count" to it.toString()) }
        },
    )
