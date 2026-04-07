package com.letta.mobile.data.model

data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String,
    val isReasoning: Boolean = false,
    val toolCalls: List<UiToolCall>? = null
)

data class UiToolCall(
    val name: String,
    val arguments: String,
    val result: String?
)
