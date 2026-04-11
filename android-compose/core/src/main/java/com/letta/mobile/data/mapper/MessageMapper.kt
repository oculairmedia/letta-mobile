package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
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
        is ToolReturnMessage -> {
            val toolCallId = toolReturn.toolCallId
            val context = toolCallId?.let(toolCallsById::get)
            AppMessage(
                id = id,
                date = date?.toInstant() ?: Instant.now(),
                messageType = MessageType.TOOL_RETURN,
                content = toolReturn.funcResponse ?: "",
                toolName = context?.name,
                toolCallId = toolCallId,
            )
        }
        else -> null
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
        messageType == MessageType.TOOL_CALL && toolName != null -> {
            listOf(UiToolCall(name = toolName, arguments = content, result = null))
        }
        messageType == MessageType.TOOL_RETURN && toolName != null -> {
            listOf(UiToolCall(name = toolName, arguments = "", result = content))
        }
        else -> null
    }
    val displayContent = when {
        messageType == MessageType.TOOL_CALL && toolCalls != null -> ""
        messageType == MessageType.TOOL_RETURN && toolCalls != null -> ""
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
