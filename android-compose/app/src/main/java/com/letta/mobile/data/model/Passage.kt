package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Passage(
    val id: String,
    val text: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val metadata: Map<String, String>? = null,
)

@Serializable
data class PassageCreateParams(
    val text: String,
)
