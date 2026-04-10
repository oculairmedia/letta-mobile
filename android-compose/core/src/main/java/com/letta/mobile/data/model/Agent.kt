package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Agent(
    val id: String,
    val name: String,
    val description: String? = null,
    val model: String? = null,
    val embedding: String? = null,
    @SerialName("model_settings") val modelSettings: ModelSettings? = null,
    @SerialName("llm_config") val llmConfig: LlmConfig? = null,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    val blocks: List<Block> = emptyList(),
    val tools: List<Tool> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val system: String? = null,
    @SerialName("enable_sleeptime") val enableSleeptime: Boolean? = null,
    @SerialName("agent_type") val agentType: String? = null,
    @SerialName("message_ids") val messageIds: List<String> = emptyList(),
)

@Serializable
data class AgentCreateParams(
    val name: String? = null,
    val model: String? = null,
    val embedding: String? = null,
    @SerialName("model_settings") val modelSettings: ModelSettings? = null,
    @SerialName("memory_blocks") val memoryBlocks: List<BlockCreateParams>? = null,
    @SerialName("tool_ids") val toolIds: List<String>? = null,
    val tags: List<String>? = null,
    val system: String? = null,
    val description: String? = null,
    @SerialName("enable_sleeptime") val enableSleeptime: Boolean? = null,
    @SerialName("include_base_tools") val includeBaseTools: Boolean? = null,
)

@Serializable
data class AgentUpdateParams(
    val name: String? = null,
    val description: String? = null,
    val model: String? = null,
    val embedding: String? = null,
    @SerialName("model_settings") val modelSettings: ModelSettings? = null,
    val system: String? = null,
    val tags: List<String>? = null,
    @SerialName("enable_sleeptime") val enableSleeptime: Boolean? = null,
)
