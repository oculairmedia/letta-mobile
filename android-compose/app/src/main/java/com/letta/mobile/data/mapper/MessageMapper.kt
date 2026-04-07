package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.ui.screens.chat.Message
import com.letta.mobile.ui.screens.chat.ToolCall
import java.time.Instant

fun LettaMessage.toAppMessage(): AppMessage? {
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
        is ToolCallMessage -> AppMessage(
            id = id,
            date = date?.toInstant() ?: Instant.now(),
            messageType = MessageType.TOOL_CALL,
            content = toolCall.arguments,
            toolName = toolCall.name,
            toolCallId = toolCall.effectiveId
        )
        is ToolReturnMessage -> AppMessage(
            id = id,
            date = date?.toInstant() ?: Instant.now(),
            messageType = MessageType.TOOL_RETURN,
            content = toolReturn.funcResponse ?: "",
            toolCallId = toolReturn.toolCallId
        )
        else -> null
    }
}

fun AppMessage.toUiMessage(): Message {
    val role = when (messageType) {
        MessageType.USER -> "user"
        MessageType.ASSISTANT -> "assistant"
        MessageType.REASONING -> "assistant"
        MessageType.TOOL_CALL -> "tool"
        MessageType.TOOL_RETURN -> "tool"
    }
    val toolCalls = if (messageType == MessageType.TOOL_CALL && toolName != null) {
        listOf(ToolCall(name = toolName, arguments = content, result = null))
    } else null

    return Message(
        id = id,
        role = role,
        content = content,
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
