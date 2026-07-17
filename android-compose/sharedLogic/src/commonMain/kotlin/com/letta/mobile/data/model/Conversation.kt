package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-bn008.3: conversation routing class. Direct agent-to-agent inbound
 * messages land ONLY in an INTERACTIVE conversation (human/peer-initiated) and are
 * NEVER routed to an AUTONOMOUS one (heartbeat/goal/dispatch). Tagged at creation;
 * defaults to INTERACTIVE so existing/untagged conversations route correctly.
 */
@Serializable
enum class ConversationClass {
    @SerialName("interactive") INTERACTIVE,
    @SerialName("autonomous") AUTONOMOUS,
}

@Serializable
data class Conversation(
    val id: ConversationId,
    @SerialName("agent_id") val agentId: AgentId,
    @SerialName("agent_name") val agentName: String? = null,
    val summary: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    val archived: Boolean? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("in_context_message_ids") val inContextMessageIds: List<String> = emptyList(),
    @SerialName("isolated_block_ids") val isolatedBlockIds: List<String> = emptyList(),
    // letta-mobile-bn008.3: routing class; null/absent = INTERACTIVE (see effectiveClass).
    @SerialName("conversation_class") val conversationClass: ConversationClass? = null,
) {
    /** INTERACTIVE unless explicitly tagged AUTONOMOUS. */
    val effectiveClass: ConversationClass get() = conversationClass ?: ConversationClass.INTERACTIVE
}

@Serializable
data class ConversationCreateParams(
    @SerialName("agent_id") val agentId: AgentId,
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
