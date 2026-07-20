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

internal fun LettaMessage.toInspectorMessage(): ConversationInspectorMessage {
    val baseDetails = buildList {
        date?.let { add("Date" to it) }
        runId?.let { add("Run ID" to it) }
        stepId?.let { add("Step ID" to it) }
        otid?.let { add("OTID" to it) }
    }
    return when (this) {
        is UserMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = content.ifBlank { "User message" },
            detailLines = baseDetails + listOfNotNull(senderId?.let { "Sender ID" to it }),
        )
        is AssistantMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = content.ifBlank { "Assistant message" },
            detailLines = baseDetails + listOfNotNull(senderId?.let { "Sender ID" to it }),
        )
        is ReasoningMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = reasoning,
            detailLines = baseDetails + listOfNotNull(senderId?.let { "Sender ID" to it }),
        )
        is ToolCallMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = effectiveToolCalls.firstOrNull()?.name ?: "Tool call",
            detailLines = baseDetails + listOf(
                "Tool Call ID" to (effectiveToolCalls.firstOrNull()?.effectiveId ?: ""),
                "Arguments" to (effectiveToolCalls.firstOrNull()?.arguments ?: ""),
            ) + listOfNotNull(senderId?.let { "Sender ID" to it }),
        )
        is ToolReturnMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = toolReturn.funcResponse ?: toolReturn.status,
            detailLines = baseDetails + buildList {
                add("Tool Call ID" to toolReturn.toolCallId)
                add("Status" to toolReturn.status)
                toolReturn.funcResponse?.let { add("Function Response" to it) }
                toolReturn.stdout?.takeIf { it.isNotEmpty() }?.let { add("Stdout" to it.joinToString("\n")) }
                toolReturn.stderr?.takeIf { it.isNotEmpty() }?.let { add("Stderr" to it.joinToString("\n")) }
                senderId?.let { add("Sender ID" to it) }
            },
        )
        is ApprovalRequestMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = "Approval request",
            detailLines = baseDetails + buildList {
                add("Tool Call Count" to effectiveToolCalls.size.toString())
                effectiveToolCalls.forEachIndexed { index, toolCall ->
                    add("Tool ${index + 1}" to "${toolCall.name}: ${toolCall.arguments}")
                }
                senderId?.let { add("Sender ID" to it) }
            },
        )
        is ApprovalResponseMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = "Approval response",
            detailLines = baseDetails + buildList {
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
        is HiddenReasoningMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = hiddenReasoning ?: state,
            detailLines = baseDetails + buildList {
                add("State" to state)
                hiddenReasoning?.let { add("Hidden Reasoning" to it) }
                senderId?.let { add("Sender ID" to it) }
            },
        )
        is EventMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = eventType,
            detailLines = baseDetails + buildList {
                add("Event Type" to eventType)
                eventData?.forEach { (key, value) ->
                    val rendered = (value as? JsonPrimitive)?.content ?: value.toString()
                    add(key to rendered)
                }
                senderId?.let { add("Sender ID" to it) }
            },
        )
        is SystemMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = content.ifBlank { "System message" },
            detailLines = baseDetails + listOfNotNull(senderId?.let { "Sender ID" to it }),
        )
        is PingMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = "Ping",
            detailLines = baseDetails,
        )
        is UnknownMessage -> ConversationInspectorMessage(
            id = id,
            messageType = messageType,
            date = date,
            runId = runId,
            stepId = stepId,
            otid = otid,
            summary = "Unknown message type",
            detailLines = baseDetails,
        )
        is ErrorMessage -> {
            val extra = buildList<Pair<String, String>> {
                add("Error Text" to text)
                code?.let { add("Code" to it) }
            }
            ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = "Error: ${text.take(120)}",
                detailLines = baseDetails + extra,
            )
        }
        is StopReason -> {
            ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = "Stop: $reason",
                detailLines = baseDetails + listOf("Reason" to reason),
            )
        }
        is UsageStatistics -> {
            ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = "Usage: ${totalTokens ?: 0} tokens",
                detailLines = baseDetails + buildList {
                    promptTokens?.let { add("Prompt Tokens" to it.toString()) }
                    completionTokens?.let { add("Completion Tokens" to it.toString()) }
                    totalTokens?.let { add("Total Tokens" to it.toString()) }
                    stepCount?.let { add("Step Count" to it.toString()) }
                },
            )
        }
    }
}
