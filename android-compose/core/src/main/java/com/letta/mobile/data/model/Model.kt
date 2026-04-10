package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LlmModel(
    val id: String = "",
    val name: String = "",
    val handle: String? = null,
    @SerialName("display_name") val displayNameOverride: String? = null,
    @SerialName("provider_type") val providerType: String = "",
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("provider_category") val providerCategory: String? = null,
    @SerialName("model_endpoint_type") val modelEndpointType: String? = null,
    @SerialName("model_endpoint") val modelEndpoint: String? = null,
    @SerialName("model_wrapper") val modelWrapper: String? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
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
) {
    val displayName: String get() = displayNameOverride ?: handle ?: name.ifBlank { id }
}

@Serializable
data class EmbeddingModel(
    val id: String = "",
    val name: String = "",
    val handle: String? = null,
    @SerialName("embedding_model") val embeddingModel: String? = null,
    @SerialName("provider_type") val providerType: String = "",
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("provider_category") val providerCategory: String? = null,
    @SerialName("embedding_endpoint_type") val embeddingEndpointType: String? = null,
    @SerialName("embedding_endpoint") val embeddingEndpoint: String? = null,
    @SerialName("embedding_dim") val embeddingDim: Int? = null,
    @SerialName("embedding_chunk_size") val embeddingChunkSize: Int? = null,
    @SerialName("batch_size") val batchSize: Int? = null,
    @SerialName("azure_endpoint") val azureEndpoint: String? = null,
    @SerialName("azure_version") val azureVersion: String? = null,
    @SerialName("azure_deployment") val azureDeployment: String? = null,
) {
    val displayName: String get() = handle ?: name.ifBlank { id }
}
