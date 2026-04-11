package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import java.time.Instant

private data class ToolCallContext(
    val name: String,
    val arguments: String,
)

fun List<LettaMessage>.toAppMessages(): List<AppMessage> {
    val toolCallsById = mutableMapOf<String, ToolCallContext>()
    return mapNotNull { it.toAppMessage(toolCallsById) }
}

fun LettaMessage.toAppMessage(): AppMessage? {
    return toAppMessage(mutableMapOf())
}

private fun LettaMessage.toAppMessage(toolCallsById: MutableMap<String, ToolCallContext>): AppMessage? {
    return when (this) {
        is UserMessage -> AppMessage(
            id = id,
            date = date?.toInstant() ?: Instant.now(),
            messageType = MessageType.USER,
            content = content
        )
        is AssistantMessage -> AppMessage(
            id = id,
            date = date?.toInstant() ?: Instant.now(),
            messageType = MessageType.ASSISTANT,
            content = content
        )
        is ReasoningMessage -> AppMessage(
            id = id,
            date = date?.toInstant() ?: Instant.now(),
            messageType = MessageType.REASONING,
            content = reasoning
        )
        is ToolCallMessage -> {
            val toolCall = effectiveToolCalls.firstOrNull()
            val toolCallId = toolCall?.effectiveId
            val toolName = toolCall?.name
            val arguments = toolCall?.arguments.orEmpty()
            if (!toolCallId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                toolCallsById[toolCallId] = ToolCallContext(name = toolName, arguments = arguments)
            }
            AppMessage(
                id = id,
                date = date?.toInstant() ?: Instant.now(),
                messageType = MessageType.TOOL_CALL,
                content = arguments,
                toolName = toolName,
                toolCallId = toolCallId,
            )
        }
        is ApprovalRequestMessage -> {
            // ApprovalRequestMessage carries tool call details (name, arguments, id)
            // just like ToolCallMessage — the server returns these instead of
            // tool_call_message for agents with approval enabled.
            val toolCall = effectiveToolCalls.firstOrNull()
            val toolCallId = toolCall?.effectiveId
            val toolName = toolCall?.name
            val arguments = toolCall?.arguments.orEmpty()
            if (!toolCallId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                toolCallsById[toolCallId] = ToolCallContext(name = toolName, arguments = arguments)
            }
            AppMessage(
                id = id,
                date = date?.toInstant() ?: Instant.now(),
                messageType = MessageType.TOOL_CALL,
                content = arguments,
                toolName = toolName,
                toolCallId = toolCallId,
            )
        }
        is ToolReturnMessage -> {
            val toolCallId = toolReturn.toolCallId
            val context = toolCallId?.let(toolCallsById::get)
            AppMessage(
                id = id,
                date = date?.toInstant() ?: Instant.now(),
                messageType = MessageType.TOOL_RETURN,
                content = toolReturn.funcResponse ?: "",
                toolName = context?.name ?: name,
                toolCallId = toolCallId,
                toolReturnStatus = toolReturn.status,
            )
        }
        else -> null
    }
}

fun List<AppMessage>.toUiMessages(): List<UiMessage> {
    val returnsByCallId = mutableMapOf<String, AppMessage>()
    for (msg in this) {
        if (msg.messageType == MessageType.TOOL_RETURN && !msg.toolCallId.isNullOrBlank()) {
            returnsByCallId[msg.toolCallId] = msg
        }
    }

    val consumedReturnIds = mutableSetOf<String>()

    val result = mutableListOf<UiMessage>()
    for (msg in this) {
        when (msg.messageType) {
            MessageType.TOOL_CALL -> {
                val matchedReturn = msg.toolCallId?.let(returnsByCallId::get)
                if (matchedReturn != null) {
                    consumedReturnIds.add(matchedReturn.id)
                }
                val name = msg.toolName
                val arguments = msg.content
                val returnContent = matchedReturn?.content
                val returnStatus = matchedReturn?.toolReturnStatus

                // send_message is Letta's reply tool — promote to assistant bubble
                if (name == "send_message" && returnContent != null) {
                    val visibleText = extractSendMessageText(arguments, returnContent)
                    if (visibleText.isNotBlank()) {
                        result.add(UiMessage(
                            id = msg.id,
                            role = "assistant",
                            content = visibleText,
                            timestamp = msg.date.toString(),
                        ))
                        continue
                    }
                }

                val toolCall = UiToolCall(
                    name = name ?: "tool",
                    arguments = arguments,
                    result = returnContent,
                    status = returnStatus,
                )
                result.add(UiMessage(
                    id = msg.id,
                    role = "tool",
                    content = "",
                    timestamp = msg.date.toString(),
                    toolCalls = listOf(toolCall),
                ))
            }

            MessageType.TOOL_RETURN -> {
                if (msg.id in consumedReturnIds) continue
                val name = msg.toolName ?: "tool"

                if (name == "send_message" && msg.content.isNotBlank()) {
                    result.add(UiMessage(
                        id = msg.id,
                        role = "assistant",
                        content = msg.content,
                        timestamp = msg.date.toString(),
                    ))
                    continue
                }

                val toolCall = UiToolCall(
                    name = name,
                    arguments = "",
                    result = msg.content.ifBlank { null },
                    status = msg.toolReturnStatus,
                )
                result.add(UiMessage(
                    id = msg.id,
                    role = "tool",
                    content = "",
                    timestamp = msg.date.toString(),
                    toolCalls = listOf(toolCall),
                ))
            }

            MessageType.USER -> result.add(msg.toUiMessage())
            MessageType.ASSISTANT -> result.add(msg.toUiMessage())
            MessageType.REASONING -> result.add(msg.toUiMessage())
        }
    }
    return result
}

private fun extractSendMessageText(arguments: String, returnContent: String): String {
    return try {
        val msgStart = arguments.indexOf("\"message\"")
        if (msgStart < 0) return returnContent
        val colonIdx = arguments.indexOf(':', msgStart)
        if (colonIdx < 0) return returnContent
        val valStart = arguments.indexOf('"', colonIdx + 1)
        if (valStart < 0) return returnContent
        var i = valStart + 1
        val sb = StringBuilder()
        while (i < arguments.length) {
            val c = arguments[i]
            if (c == '\\' && i + 1 < arguments.length) {
                val next = arguments[i + 1]
                when (next) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> { sb.append('\\'); sb.append(next) }
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        sb.toString().ifBlank { returnContent }
    } catch (_: Exception) {
        returnContent
    }
}

fun AppMessage.toUiMessage(): UiMessage {
    val role = when (messageType) {
        MessageType.USER -> "user"
        MessageType.ASSISTANT -> "assistant"
        MessageType.REASONING -> "assistant"
        MessageType.TOOL_CALL -> "tool"
        MessageType.TOOL_RETURN -> "tool"
    }
    val toolCalls = when {
        messageType == MessageType.TOOL_CALL -> {
            listOf(UiToolCall(name = toolName ?: "tool", arguments = content, result = null))
        }
        messageType == MessageType.TOOL_RETURN -> {
            listOf(UiToolCall(name = toolName ?: "tool", arguments = "", result = content.ifBlank { null }))
        }
        else -> null
    }
    val displayContent = when {
        messageType == MessageType.TOOL_CALL -> ""
        messageType == MessageType.TOOL_RETURN -> ""
        else -> content
    }

    return UiMessage(
        id = id,
        role = role,
        content = displayContent,
        timestamp = date.toString(),
        isReasoning = messageType == MessageType.REASONING,
        toolCalls = toolCalls
    )
}

private fun String.toInstant(): Instant {
    return try {
        Instant.parse(this)
    } catch (e: Exception) {
        Instant.now()
    }
}
