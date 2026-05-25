package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val summary: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    val archived: Boolean? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("in_context_message_ids") val inContextMessageIds: List<String> = emptyList(),
    @SerialName("isolated_block_ids") val isolatedBlockIds: List<String> = emptyList(),
)

@Serializable
data class ConversationCreateParams(
    @SerialName("agent_id") val agentId: String,
    val summary: String? = null,
    @SerialName("isolated_block_labels") val isolatedBlockLabels: List<String>? = null,
    val model: String? = null,
    @SerialName("model_settings") val modelSettings: ModelSettings? = null,
)

@Serializable
data class ConversationUpdateParams(
    val summary: String? = null,
    val archived: Boolean? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    val model: String? = null,
    @SerialName("model_settings") val modelSettings: ModelSettings? = null,
)
