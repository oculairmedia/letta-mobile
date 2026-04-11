package com.letta.mobile.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String,
    val isReasoning: Boolean = false,
    val toolCalls: List<UiToolCall>? = null
)

@Immutable
data class UiToolCall(
    val name: String,
    val arguments: String,
    val result: String?,
    val status: String? = null,
)
