package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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
    val senderId: String?
    val isErr: Boolean?
    val seqId: Int?
}

@Serializable
data class SystemMessage(
    override val id: String,
    @SerialName("content") val contentRaw: JsonElement? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "system_message",
) : LettaMessage {
    val content: String
        get() = extractContent(contentRaw)
}

@Serializable
data class UserMessage(
    override val id: String,
    @SerialName("content") val contentRaw: JsonElement? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "user_message",
) : LettaMessage {
    val content: String
        get() = extractContent(contentRaw)
}

@Serializable
data class AssistantMessage(
    override val id: String,
    @SerialName("content") val contentRaw: JsonElement? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "assistant_message",
) : LettaMessage {
    val content: String
        get() = extractContent(contentRaw)
}

private fun extractContent(raw: JsonElement?): String {
    return when {
        raw == null -> ""
        raw is JsonPrimitive && raw.isString -> raw.content
        raw is kotlinx.serialization.json.JsonArray -> {
            raw.mapNotNull { element ->
                if (element is JsonObject) {
                    element["text"]?.jsonPrimitive?.content
                } else if (element is JsonPrimitive) {
                    element.content
                } else null
            }.joinToString("\n")
        }
        else -> raw.toString()
    }
}

@Serializable
data class ReasoningMessage(
    override val id: String,
    val reasoning: String,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    val source: String? = null,
    val signature: String? = null,
    @SerialName("message_type") override val messageType: String = "reasoning_message",
) : LettaMessage

@Serializable
data class ToolCallMessage(
    override val id: String,
    @SerialName("tool_call") val toolCall: ToolCall? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "tool_call_message",
) : LettaMessage {
    val effectiveToolCalls: List<ToolCall>
        get() = toolCalls ?: listOfNotNull(toolCall)
}

@Serializable
data class ToolReturnMessage(
    override val id: String,
    @SerialName("tool_return") val toolReturnRaw: JsonElement? = null,
    val status: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val stdout: List<String>? = null,
    val stderr: List<String>? = null,
    @SerialName("tool_returns") val toolReturns: List<ToolReturn>? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "tool_return_message",
) : LettaMessage {
    val toolReturn: ToolReturn
        get() {
            val rawFuncResponse = when {
                toolReturnRaw is JsonPrimitive && toolReturnRaw.isString -> toolReturnRaw.content
                else -> null
            }
            val fromList = toolReturns?.firstOrNull()
            if (fromList != null) {
                // Enrich with funcResponse from tool_return raw if missing
                return if (fromList.funcResponse == null && rawFuncResponse != null) {
                    fromList.copy(funcResponse = rawFuncResponse, stdout = fromList.stdout ?: stdout, stderr = fromList.stderr ?: stderr)
                } else {
                    fromList
                }
            }
            return when {
                rawFuncResponse != null -> ToolReturn(
                    toolCallId = toolCallId ?: "",
                    status = status ?: "success",
                    funcResponse = rawFuncResponse,
                    stdout = stdout,
                    stderr = stderr,
                )
                toolReturnRaw is JsonObject -> Json.decodeFromJsonElement(ToolReturn.serializer(), toolReturnRaw)
                else -> ToolReturn(toolCallId = toolCallId ?: "", status = status ?: "unknown", funcResponse = null, stdout = stdout, stderr = stderr)
            }
        }
}

@Serializable
data class ApprovalRequestMessage(
    override val id: String,
    @SerialName("tool_call") val toolCall: ToolCall? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "approval_request_message",
) : LettaMessage {
    val effectiveToolCalls: List<ToolCall>
        get() = toolCalls ?: listOfNotNull(toolCall)
}

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
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "hidden_reasoning_message",
) : LettaMessage

@Serializable
data class EventMessage(
    override val id: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("event_data") val eventData: Map<String, JsonElement>? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "event_message",
) : LettaMessage

@Serializable
data class ApprovalResponseMessage(
    override val id: String,
    val approvals: List<ApprovalResult>? = null,
    val approve: Boolean? = null,
    @SerialName("approval_request_id") val approvalRequestId: String? = null,
    val reason: String? = null,
    override val date: String? = null,
    val name: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "approval_response_message",
) : LettaMessage

@Serializable
data class ApprovalResult(
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_return") val toolReturn: String? = null,
    val status: String? = null,
    val type: String? = null,
    val approve: Boolean? = null,
    val reason: String? = null,
    val stdout: List<String>? = null,
    val stderr: List<String>? = null,
)

@Serializable
data class UnknownMessage(
    override val id: String,
    override val date: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    override val senderId: String? = null,
    override val isErr: Boolean? = null,
    override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "unknown",
) : LettaMessage

@Serializable
data class PingMessage(
    override val id: String = "ping",
    override val date: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    override val senderId: String? = null,
    override val isErr: Boolean? = null,
    override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "ping",
) : LettaMessage

@Serializable
data class ToolCall(
    val id: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val type: String = "function",
) {
    val effectiveId: String get() = id ?: toolCallId ?: ""
}

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
    val type: String = "message",
    val role: String,
    val content: JsonElement,
    val name: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    val otid: String? = null,
    @SerialName("batch_item_id") val batchItemId: String? = null,
    @SerialName("group_id") val groupId: String? = null,
)

@Serializable
data class ApprovalCreate(
    val type: String = "approval",
    val approvals: List<ApprovalResult>? = null,
    val approve: Boolean? = null,
    @SerialName("approval_request_id") val approvalRequestId: String? = null,
    val reason: String? = null,
)

@Serializable
data class MessageCreateRequest(
    val messages: List<JsonElement>? = null,
    val input: String? = null,
    val streaming: Boolean? = null,
    @SerialName("stream_tokens") val streamTokens: Boolean? = null,
    @SerialName("include_pings") val includePings: Boolean? = null,
    val background: Boolean? = null,
    @SerialName("max_steps") val maxSteps: Int? = null,
    @SerialName("use_assistant_message") val useAssistantMessage: Boolean? = null,
    @SerialName("assistant_message_tool_name") val assistantMessageToolName: String? = null,
    @SerialName("assistant_message_tool_kwarg") val assistantMessageToolKwarg: String? = null,
    @SerialName("include_return_message_types") val includeReturnMessageTypes: List<String>? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("callback_url") val callbackUrl: String? = null,
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
    @SerialName("prompt_tokens_details") val promptTokensDetails: UsagePromptTokenDetails? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: UsageCompletionTokenDetails? = null,
    @SerialName("cached_input_tokens") val cachedInputTokens: Int? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
    @SerialName("context_tokens") val contextTokens: Int? = null,
    @SerialName("run_ids") val runIds: List<String> = emptyList(),
    @SerialName("message_type") val messageType: String = "usage_statistics",
)

@Serializable
data class UsagePromptTokenDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
)

@Serializable
data class UsageCompletionTokenDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
)

/**
 * Custom serializer for LettaMessage that dispatches based on message_type field.
 */
object LettaMessageSerializer : JsonContentPolymorphicSerializer<LettaMessage>(LettaMessage::class) {
    override fun selectDeserializer(element: JsonElement) = when (element.jsonObject["message_type"]?.jsonPrimitive?.content) {
        "system_message" -> SystemMessage.serializer()
        "user_message" -> UserMessage.serializer()
        "assistant_message" -> AssistantMessage.serializer()
        "reasoning_message" -> ReasoningMessage.serializer()
        "tool_call_message" -> ToolCallMessage.serializer()
        "tool_return_message" -> ToolReturnMessage.serializer()
        "approval_request_message" -> ApprovalRequestMessage.serializer()
        "approval_response_message" -> ApprovalResponseMessage.serializer()
        "hidden_reasoning_message" -> HiddenReasoningMessage.serializer()
        "event_message" -> EventMessage.serializer()
        "ping" -> PingMessage.serializer()
        else -> UnknownMessage.serializer() // fallback for unknown types
    }
}
