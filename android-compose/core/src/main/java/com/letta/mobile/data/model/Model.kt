package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LlmModel(
    val id: String,
    val name: String,
    @SerialName("provider_type") val providerType: String,
    @SerialName("context_window") val contextWindow: Int? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
)

@Serializable
data class EmbeddingModel(
    val id: String,
    val name: String,
    @SerialName("provider_type") val providerType: String,
    @SerialName("embedding_dim") val embeddingDim: Int? = null,
)
