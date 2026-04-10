package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class ScheduleListResponse(
    @SerialName("has_next_page") val hasNextPage: Boolean = false,
    @SerialName("scheduled_messages") val scheduledMessages: List<ScheduledMessage> = emptyList(),
)
