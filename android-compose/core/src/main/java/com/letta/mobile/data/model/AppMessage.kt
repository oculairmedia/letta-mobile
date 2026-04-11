package com.letta.mobile.data.model

import java.time.Instant

/**
 * Application-level message model for UI display.
 * Simplified from the API's LettaMessage types.
 */
data class AppMessage(
    val id: String,
    val date: Instant,
    val messageType: MessageType,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolReturnStatus: String? = null,
)

enum class MessageType {
    USER,
    ASSISTANT,
    REASONING,
    TOOL_CALL,
    TOOL_RETURN
}
