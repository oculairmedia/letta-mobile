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
    /**
     * letta-mobile-fxoew.6: the store's `"hidden": true` (subagent / background
     * conversations). Lists drop hidden conversations ([HiddenListFilter]);
     * opening one by id still works.
     */
    val hidden: Boolean? = null,
    /**
     * letta-mobile-dxz5s: the conversation's own model, when the host overrides the agent's
     * (`update_model` with a conversation id, from any client: this app, the CLI, desktop).
     * letta-code's local backend keeps it in `conversation.json` and its `conversation_list` /
     * `conversation_retrieve` answer the whole record, so it reaches the client as is. Null means
     * the conversation runs its agent's model.
     */
    val model: String? = null,
    /** letta-mobile-dxz5s: the override's settings (window, effort); see [ConversationModelSettings]. */
    @SerialName("model_settings") val modelSettings: ConversationModelSettings? = null,
    /** letta-mobile-dxz5s: the conversation's window, set with the model or by `/context-limit`. */
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
) {
    /** INTERACTIVE unless explicitly tagged AUTONOMOUS. */
    val effectiveClass: ConversationClass get() = conversationClass ?: ConversationClass.INTERACTIVE

    /** The conversation's own window: the record's limit, else its model settings'; null when it has none. */
    val hostContextWindowLimit: Int?
        get() = contextWindowLimit?.takeIf { it > 0 } ?: modelSettings?.contextWindowLimit?.takeIf { it > 0 }
}

/**
 * letta-mobile-dxz5s: the read side of a conversation's `model_settings`, as letta-code stores it
 * (`{provider_type, context_window_limit, max_tokens, effort, reasoning_effort, thinking, ...}`).
 * Only what the client shows is decoded; every other key is ignored, and every field defaults so
 * an older host or a partial record still decodes.
 */
@Serializable
data class ConversationModelSettings(
    @SerialName("provider_type") val providerType: String? = null,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val effort: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
) {
    /** The reasoning effort in force: `reasoning_effort`, else Anthropic's `effort`. */
    val effectiveEffort: String? get() = reasoningEffort?.takeIf { it.isNotBlank() } ?: effort?.takeIf { it.isNotBlank() }
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
