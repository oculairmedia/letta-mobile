package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sealed interface for all Letta message types.
 * Uses custom polymorphic serialization based on the message_type field.
 */
@Serializable(with = LettaMessageSerializer::class)
sealed interface LettaMessage {
    val id: String
    val messageType: String
    val date: String?
    val runId: String?
    val stepId: String?
    val otid: String?
}

@Serializable
data class UserMessage(
    override val id: String,
    val content: String,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("message_type") override val messageType: String = "user_message",
) : LettaMessage

@Serializable
data class AssistantMessage(
    override val id: String,
    val content: String,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("message_type") override val messageType: String = "assistant_message",
) : LettaMessage

@Serializable
data class ReasoningMessage(
    override val id: String,
    val reasoning: String,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("message_type") override val messageType: String = "reasoning_message",
) : LettaMessage

@Serializable
data class ToolCallMessage(
    override val id: String,
    @SerialName("tool_call") val toolCall: ToolCall,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("message_type") override val messageType: String = "tool_call_message",
) : LettaMessage

@Serializable
data class ToolReturnMessage(
    override val id: String,
    @SerialName("tool_return") val toolReturn: ToolReturn,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("message_type") override val messageType: String = "tool_return_message",
) : LettaMessage

@Serializable
data class ApprovalRequestMessage(
    override val id: String,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("message_type") override val messageType: String = "approval_request_message",
) : LettaMessage

@Serializable
data class HiddenReasoningMessage(
    override val id: String,
    val state: String,
    @SerialName("hidden_reasoning") val hiddenReasoning: String? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("message_type") override val messageType: String = "hidden_reasoning_message",
) : LettaMessage

@Serializable
data class EventMessage(
    override val id: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("event_data") val eventData: Map<String, String>? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("message_type") override val messageType: String = "event_message",
) : LettaMessage

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val type: String = "function",
)

@Serializable
data class ToolReturn(
    @SerialName("tool_call_id") val toolCallId: String,
    val status: String,
    @SerialName("func_response") val funcResponse: String? = null,
    val stdout: List<String>? = null,
    val stderr: List<String>? = null,
)

@Serializable
data class MessageCreate(
    val role: String,
    val content: String,
    val name: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    val otid: String? = null,
)

@Serializable
data class MessageCreateRequest(
    val messages: List<MessageCreate>? = null,
    val input: String? = null,
    val streaming: Boolean? = null,
    @SerialName("stream_tokens") val streamTokens: Boolean? = null,
    @SerialName("include_pings") val includePings: Boolean? = null,
    val background: Boolean? = null,
    @SerialName("max_steps") val maxSteps: Int? = null,
)

@Serializable
data class LettaResponse(
    val messages: List<LettaMessage>,
    @SerialName("stop_reason") val stopReason: StopReason,
    val usage: UsageStatistics,
)

@Serializable
data class StopReason(
    @SerialName("stop_reason") val reason: String,
    @SerialName("message_type") val messageType: String = "stop_reason",
)

@Serializable
data class UsageStatistics(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("step_count") val stepCount: Int? = null,
    @SerialName("cached_input_tokens") val cachedInputTokens: Int? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
    @SerialName("context_tokens") val contextTokens: Int? = null,
    @SerialName("run_ids") val runIds: List<String>? = null,
    @SerialName("message_type") val messageType: String = "usage_statistics",
)

/**
 * Custom serializer for LettaMessage that dispatches based on message_type field.
 */
object LettaMessageSerializer : JsonContentPolymorphicSerializer<LettaMessage>(LettaMessage::class) {
    override fun selectDeserializer(element: JsonElement) = when (element.jsonObject["message_type"]?.jsonPrimitive?.content) {
        "user_message" -> UserMessage.serializer()
        "assistant_message" -> AssistantMessage.serializer()
        "reasoning_message" -> ReasoningMessage.serializer()
        "tool_call_message" -> ToolCallMessage.serializer()
        "tool_return_message" -> ToolReturnMessage.serializer()
        "approval_request_message" -> ApprovalRequestMessage.serializer()
        "hidden_reasoning_message" -> HiddenReasoningMessage.serializer()
        "event_message" -> EventMessage.serializer()
        else -> UserMessage.serializer() // fallback
    }
}
