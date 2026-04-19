package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder

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
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "system_message",
) : LettaMessage {
    val content: String
        get() = extractContent(contentRaw)

    val attachments: List<MessageContentPart.Image>
        get() = extractAttachments(contentRaw)
}

@Serializable
data class UserMessage(
    override val id: String,
    @SerialName("content") val contentRaw: JsonElement? = null,
    override val date: String? = null,
    val name: String? = null,
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "user_message",
) : LettaMessage {
    val content: String
        get() = extractContent(contentRaw)

    val attachments: List<MessageContentPart.Image>
        get() = extractAttachments(contentRaw)
}

@Serializable
data class AssistantMessage(
    override val id: String,
    @SerialName("content") val contentRaw: JsonElement? = null,
    override val date: String? = null,
    val name: String? = null,
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
    override val otid: String? = null,
    @SerialName("sender_id") override val senderId: String? = null,
    @SerialName("is_err") override val isErr: Boolean? = null,
    @SerialName("seq_id") override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "assistant_message",
) : LettaMessage {
    val content: String
        get() = extractContent(contentRaw)

    val attachments: List<MessageContentPart.Image>
        get() = extractAttachments(contentRaw)
}

private fun extractContent(raw: JsonElement?): String {
    return when {
        raw == null -> ""
        raw is JsonPrimitive && raw.isString -> raw.content
        raw is JsonArray -> {
            raw.mapNotNull { element ->
                if (element is JsonObject) {
                    element["text"]?.jsonPrimitive?.contentOrNull
                } else if (element is JsonPrimitive) {
                    element.content
                } else null
            }.joinToString("\n")
        }
        else -> raw.toString()
    }
}

private fun extractAttachments(raw: JsonElement?): List<MessageContentPart.Image> {
    if (raw !is JsonArray) return emptyList()
    return raw.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            // Letta's native shape: { type:"image", source:{ type:"base64", media_type, data } | { type:"url", url } }
            "image" -> parseLettaImagePart(obj)
            // Legacy / OpenAI-style shape kept for backward compatibility with
            // anything previously persisted or echoed by older server builds.
            "image_url" -> {
                val url = obj["image_url"]
                    ?.jsonObject
                    ?.get("url")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: return@mapNotNull null
                parseImageDataUrl(url)
            }
            else -> null
        }
    }
}

private fun parseLettaImagePart(obj: JsonObject): MessageContentPart.Image? {
    val source = obj["source"] as? JsonObject ?: return null
    return when (source["type"]?.jsonPrimitive?.contentOrNull) {
        "base64" -> {
            val mediaType = source["media_type"]?.jsonPrimitive?.contentOrNull
            val data = source["data"]?.jsonPrimitive?.contentOrNull
            if (mediaType.isNullOrBlank() || data.isNullOrBlank()) null
            else MessageContentPart.Image(base64 = data, mediaType = mediaType)
        }
        "url" -> {
            // Remote URL: only retain if it's an inline data: URL we can decode.
            val url = source["url"]?.jsonPrimitive?.contentOrNull ?: return null
            parseImageDataUrl(url)
        }
        // "letta" — server-managed file reference. Empirically the server
        // inlines the raw base64 under `data` alongside a `file_id` that
        // would let us fetch via /v1/files if needed. Since the payload is
        // already present, decode it directly and render inline (no extra
        // network roundtrip). See bead letta-mobile-mge5.24.
        "letta" -> {
            val mediaType = source["media_type"]?.jsonPrimitive?.contentOrNull
            val data = source["data"]?.jsonPrimitive?.contentOrNull
            if (mediaType.isNullOrBlank() || data.isNullOrBlank()) null
            else MessageContentPart.Image(base64 = data, mediaType = mediaType)
        }
        else -> null
    }
}

private fun parseImageDataUrl(url: String): MessageContentPart.Image? {
    val prefix = "data:"
    val separator = ";base64,"
    if (!url.startsWith(prefix)) return null
    val separatorIndex = url.indexOf(separator)
    if (separatorIndex < 0) return null
    val mediaType = url.substring(prefix.length, separatorIndex)
    val base64 = url.substring(separatorIndex + separator.length)
    if (mediaType.isBlank() || base64.isBlank()) return null
    return MessageContentPart.Image(base64 = base64, mediaType = mediaType)
}

@Serializable
data class ReasoningMessage(
    override val id: String,
    val reasoning: String,
    override val date: String? = null,
    val name: String? = null,
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
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
    // The Letta server streams `tool_calls` as EITHER a JSON array (final
    // envelope) or a single object (streaming delta frame, where only one
    // tool-call is being accumulated). The ToolCallListSerializer below
    // tolerates both — letta-mobile-mge5: previously this caused every
    // tool-call streaming frame to fail deserialization with
    // `Expected JsonArray, but had JsonObject`, producing the "choppy and
    // discontinuous" UI updates Emmanuel reported 2026-04-18.
    @SerialName("tool_calls")
    @Serializable(with = ToolCallListSerializer::class)
    val toolCalls: List<ToolCall>? = null,
    override val date: String? = null,
    val name: String? = null,
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
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
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
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
    // Same single-object-or-array quirk as ToolCallMessage — see
    // ToolCallListSerializer. letta-mobile-mge5.
    @SerialName("tool_calls")
    @Serializable(with = ToolCallListSerializer::class)
    val toolCalls: List<ToolCall>? = null,
    override val date: String? = null,
    val name: String? = null,
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
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
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
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
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
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
    @SerialName("run_id") override val runId: String? = null,
    @SerialName("step_id") override val stepId: String? = null,
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
    override val id: String = "unknown-${java.util.UUID.randomUUID()}",
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
    override val id: String = "stop-${java.util.UUID.randomUUID()}",
    @SerialName("stop_reason") val reason: String,
    override val date: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    override val senderId: String? = null,
    override val isErr: Boolean? = null,
    override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "stop_reason",
) : LettaMessage

@Serializable
data class UsageStatistics(
    override val id: String = "usage-${java.util.UUID.randomUUID()}",
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
    // The Letta server sometimes sends `run_ids: null` — tolerate that as an
    // empty list. letta-mobile-mge5.
    @SerialName("run_ids") val runIds: List<String>? = null,
    override val date: String? = null,
    override val runId: String? = null,
    override val stepId: String? = null,
    override val otid: String? = null,
    override val senderId: String? = null,
    override val isErr: Boolean? = null,
    override val seqId: Int? = null,
    @SerialName("message_type") override val messageType: String = "usage_statistics",
) : LettaMessage

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
        // letta-mobile-mge5.22: SSE uses short form "reasoning"; REST uses
        // "reasoning_message". Accept both. Same class of drift as .16/.18.
        "reasoning_message",
        "reasoning" -> ReasoningMessage.serializer()
        // SSE abbreviates some types (letta-mobile-mge5.18): accept both
        // long and short discriminator forms used by the server.
        "tool_call_message",
        "tool_call" -> ToolCallMessage.serializer()
        "tool_return_message",
        "tool_return" -> ToolReturnMessage.serializer()
        "approval_request_message",
        "approval_request" -> ApprovalRequestMessage.serializer()
        // Server inconsistency: the REST messages endpoint uses
        // "approval_response_message" while the SSE /stream endpoint uses
        // the shorter "approval_response" for the same payload shape. Accept
        // both so ApprovalResponseMessage fires in both code paths —
        // otherwise auto-approved tool calls keep their approve/reject UI
        // because the response frame is silently dropped into
        // UnknownMessage. letta-mobile-mge5.16.
        "approval_response_message",
        "approval_response" -> ApprovalResponseMessage.serializer()
        "hidden_reasoning_message",
        "hidden_reasoning" -> HiddenReasoningMessage.serializer()
        "event_message" -> EventMessage.serializer()
        "ping" -> PingMessage.serializer()
        "stop_reason" -> StopReason.serializer()
        "usage_statistics" -> UsageStatistics.serializer()
        else -> UnknownMessage.serializer() // fallback for unknown types
    }
}

/**
 * Accepts either a JSON array of [ToolCall] or a single [ToolCall] object.
 *
 * The Letta server emits `tool_calls` as a single object during streaming
 * (mid-run delta frames where exactly one tool-call is being accumulated)
 * and as an array in terminal envelopes. Without this, every streaming
 * delta fails to deserialize (`Expected JsonArray, but had JsonObject`)
 * which produced the "choppy / discontinuous" updates pattern —
 * letta-mobile-mge5 (diagnosed 2026-04-18).
 */
object ToolCallListSerializer : KSerializer<List<ToolCall>?> {
    private val listDelegate = ListSerializer(ToolCall.serializer()).nullable
    private val objectDelegate = ToolCall.serializer()

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ToolCallList")

    override fun deserialize(decoder: Decoder): List<ToolCall>? {
        val jd = decoder as? JsonDecoder
            ?: error("ToolCallListSerializer only supports JSON")
        val element = jd.decodeJsonElement()
        return when (element) {
            is JsonArray -> jd.json.decodeFromJsonElement(listDelegate, element)
            is JsonObject -> listOf(jd.json.decodeFromJsonElement(objectDelegate, element))
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: List<ToolCall>?) {
        val je = encoder as? JsonEncoder
            ?: error("ToolCallListSerializer only supports JSON")
        je.encodeSerializableValue(listDelegate, value)
    }
}
