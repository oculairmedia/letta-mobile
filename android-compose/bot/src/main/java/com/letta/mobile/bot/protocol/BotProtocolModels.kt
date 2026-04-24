package com.letta.mobile.bot.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@Serializable
data class BotChatRequest(
    val message: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @Transient val contentItems: List<BotMessageContentItem>? = null,
)

@Serializable
data class BotChatResponse(
    val response: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
)

@Serializable
data class BotStatusResponse(
    val status: String,
    val agents: List<String>,
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("agent_details") val agentDetails: List<BotAgentInfo> = emptyList(),
    @SerialName("active_profile_ids") val activeProfileIds: List<String> = emptyList(),
    @SerialName("active_modes") val activeModes: List<String> = emptyList(),
    @SerialName("api_port") val apiPort: Int? = null,
    @SerialName("auth_required") val authRequired: Boolean = false,
    @SerialName("rate_limit_requests") val rateLimitRequests: Int = 0,
    @SerialName("rate_limit_window_seconds") val rateLimitWindowSeconds: Long = 0,
)

@Serializable
data class BotAgentInfo(
    val id: String,
    val name: String,
    val status: String,
)

@Serializable
data class BotStreamChunk(
    val text: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val event: BotStreamEvent? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_input") val toolInput: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean = false,
    @SerialName("request_id") val requestId: String? = null,
    val uuid: String? = null,
    val aborted: Boolean = false,
    val done: Boolean = false,
)

@Serializable
data class BotErrorResponse(
    val error: String,
)

@Serializable
enum class BotStreamEvent {
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool_call") TOOL_CALL,
    @SerialName("tool_result") TOOL_RESULT,
    @SerialName("reasoning") REASONING,
}

enum class ConnectionState {
    CLOSED,
    CONNECTING,
    READY,
    PROCESSING,
    RECONNECTING,
}

enum class BotGatewayErrorCode {
    AUTH_FAILED,
    BAD_MESSAGE,
    NO_SESSION,
    SESSION_BUSY,
    SESSION_INIT_FAILED,
    STREAM_ERROR,
}

class BotGatewayException(
    val code: BotGatewayErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
data class BotMessageContentItem(
    val type: String,
    val text: String? = null,
    val source: BotMessageContentSource? = null,
) {
    companion object {
        fun text(text: String): BotMessageContentItem = BotMessageContentItem(
            type = "text",
            text = text,
        )

        fun image(
            base64: String,
            mediaType: String,
            sourceType: String = "base64",
        ): BotMessageContentItem = BotMessageContentItem(
            type = "image",
            source = BotMessageContentSource(
                type = sourceType,
                mediaType = mediaType,
                data = base64,
            ),
        )
    }
}

@Serializable
data class BotMessageContentSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)
