package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Job(
    val id: String,
    val status: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    @SerialName("job_type") val jobType: String? = null,
    val background: Boolean? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("callback_url") val callbackUrl: String? = null,
    @SerialName("callback_sent_at") val callbackSentAt: String? = null,
    @SerialName("callback_status_code") val callbackStatusCode: Int? = null,
    @SerialName("callback_error") val callbackError: String? = null,
    @SerialName("ttft_ns") val ttftNs: Long? = null,
    @SerialName("total_duration_ns") val totalDurationNs: Long? = null,
    @SerialName("user_id") val userId: String? = null,
)

@Serializable
data class JobListParams(
    @SerialName("source_id") val sourceId: String? = null,
    val before: String? = null,
    val after: String? = null,
    val limit: Int? = null,
    val order: String? = null,
    @SerialName("order_by") val orderBy: String? = null,
    val active: Boolean? = null,
    val ascending: Boolean? = null,
)
