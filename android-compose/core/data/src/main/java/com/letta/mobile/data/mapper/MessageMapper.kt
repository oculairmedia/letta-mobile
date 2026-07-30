package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UiMessage

/** Public mapping facade retained for callers while implementations stay direction-focused. */
fun List<LettaMessage>.toAppMessages(): List<AppMessage> = mapToAppMessages()

fun LettaMessage.toAppMessage(): AppMessage? = mapToAppMessage(MessageMappingState())

fun LettaMessage.toAppMessage(state: MessageMappingState): AppMessage? = mapToAppMessage(state)

fun List<AppMessage>.toUiMessages(): List<UiMessage> = mapToUiMessages()

fun List<AppMessage>.toUiMessages(resolvedApprovalRequestIds: Set<String>): List<UiMessage> {
    val returnsByCallId = asSequence()
        .filter { it.messageType == com.letta.mobile.data.model.MessageType.TOOL_RETURN }
        .mapNotNull { returned -> returned.toolCallId?.let { it to returned } }
        .toMap()
    return mapToUiMessages().map { message ->
        val request = message.approvalRequest
        if (message.id !in resolvedApprovalRequestIds || request == null) return@map message
        message.copy(
            role = "tool",
            approvalRequest = null,
            toolCalls = request.toolCalls.map { call ->
                val returned = returnsByCallId[call.toolCallId]
                com.letta.mobile.data.model.UiToolCall(
                    name = call.name.ifBlank { "tool" },
                    arguments = call.arguments,
                    result = returned?.content?.ifBlank { null },
                    toolCallId = call.toolCallId,
                )
            },
        )
    }
}

fun AppMessage.toUiMessage(): UiMessage = mapToUiMessage()
