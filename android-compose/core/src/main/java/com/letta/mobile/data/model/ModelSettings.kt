package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ModelSettings(
    val temperature: Double? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("provider_type") val providerType: String? = null,
    @SerialName("max_reasoning_tokens") val maxReasoningTokens: Int? = null,
    @SerialName("enable_reasoner") val enableReasoner: Boolean? = null,
    val reasoning: JsonElement? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    val verbosity: String? = null,
)
