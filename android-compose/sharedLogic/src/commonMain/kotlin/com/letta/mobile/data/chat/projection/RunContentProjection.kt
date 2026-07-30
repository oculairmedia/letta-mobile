package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall

data class RunContentProjection(
    val messages: List<UiMessage>,
    val reasoning: List<UiMessage>,
    val toolCalls: List<UiToolCall>,
    val narration: List<UiMessage>,
)

fun projectRunContent(messages: List<UiMessage>): RunContentProjection = RunContentProjection(
    messages = messages,
    reasoning = messages.filter { it.isReasoning && it.content.isNotBlank() },
    toolCalls = messages.flatMap { it.toolCalls.orEmpty() },
    narration = messages.filter {
        !it.isReasoning && it.toolCalls.isNullOrEmpty() && it.content.isNotBlank()
    },
)
