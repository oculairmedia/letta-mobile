package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LlmConfig(
    val model: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("model_endpoint_type") val modelEndpointType: String? = null,
    @SerialName("model_endpoint") val modelEndpoint: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("provider_category") val providerCategory: String? = null,
    @SerialName("model_wrapper") val modelWrapper: String? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    @SerialName("put_inner_thoughts_in_kwargs") val putInnerThoughtsInKwargs: Boolean? = null,
    val handle: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("enable_reasoner") val enableReasoner: Boolean? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_reasoning_tokens") val maxReasoningTokens: Int? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("compatibility_type") val compatibilityType: String? = null,
    val verbosity: String? = null,
    val tier: String? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
)
