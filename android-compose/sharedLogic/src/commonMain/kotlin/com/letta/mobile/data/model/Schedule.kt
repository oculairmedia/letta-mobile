package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class ScheduleMessage(
    val content: String,
    val role: String,
    val name: String? = null,
    val otid: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    val type: String? = null,
)

@Serializable
data class ScheduleDefinition(
    val type: String,
    @SerialName("scheduled_at") val scheduledAt: Double? = null,
    @SerialName("cron_expression") val cronExpression: String? = null,
)

@Serializable
data class SchedulePayload(
    val messages: List<ScheduleMessage>,
    @SerialName("callback_url") val callbackUrl: String? = null,
    @SerialName("include_return_message_types") val includeReturnMessageTypes: List<String> = emptyList(),
    @SerialName("max_steps") val maxSteps: Double? = null,
)

@Serializable
data class ScheduledMessage(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val message: SchedulePayload,
    @SerialName("next_scheduled_time") val nextScheduledTime: String? = null,
    val schedule: ScheduleDefinition,
)

@Serializable
data class ScheduleCreateParams(
    val messages: List<ScheduleMessage>,
    val schedule: ScheduleDefinition,
    @SerialName("callback_url") val callbackUrl: String? = null,
    @SerialName("include_return_message_types") val includeReturnMessageTypes: List<String>? = null,
    @SerialName("max_steps") val maxSteps: Double? = null,
)

@Serializable(with = ScheduleListResponseSerializer::class)
data class ScheduleListResponse(
    val hasNextPage: Boolean = false,
    val scheduledMessages: List<ScheduledMessage> = emptyList(),
)

@Serializable
private data class ScheduleListResponseSurrogate(
    @SerialName("has_next_page") val hasNextPage: Boolean = false,
    @SerialName("scheduled_messages") val scheduledMessages: List<ScheduledMessage> = emptyList(),
)

object ScheduleListResponseSerializer : KSerializer<ScheduleListResponse> {
    override val descriptor: SerialDescriptor = ScheduleListResponseSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): ScheduleListResponse {
        val input = decoder as? JsonDecoder ?: error("Only JSON is supported")
        val element = input.decodeJsonElement()

        return if (element is JsonArray) {
            val messages = input.json.decodeFromJsonElement<List<ScheduledMessage>>(element)
            ScheduleListResponse(hasNextPage = false, scheduledMessages = messages)
        } else {
            val surrogate = input.json.decodeFromJsonElement<ScheduleListResponseSurrogate>(element)
            ScheduleListResponse(
                hasNextPage = surrogate.hasNextPage,
                scheduledMessages = surrogate.scheduledMessages
            )
        }
    }

    override fun serialize(encoder: Encoder, value: ScheduleListResponse) {
        val surrogate = ScheduleListResponseSurrogate(
            hasNextPage = value.hasNextPage,
            scheduledMessages = value.scheduledMessages
        )
        encoder.encodeSerializableValue(ScheduleListResponseSurrogate.serializer(), surrogate)
    }
}
