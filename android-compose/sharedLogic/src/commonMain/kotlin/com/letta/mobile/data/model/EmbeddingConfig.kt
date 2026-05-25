package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmbeddingConfig(
    @SerialName("embedding_endpoint_type") val embeddingEndpointType: String? = null,
    @SerialName("embedding_endpoint") val embeddingEndpoint: String? = null,
    @SerialName("embedding_model") val embeddingModel: String? = null,
    @SerialName("embedding_dim") val embeddingDim: Int? = null,
    @SerialName("embedding_chunk_size") val embeddingChunkSize: Int? = null,
    val handle: String? = null,
    @SerialName("batch_size") val batchSize: Int? = null,
    @SerialName("azure_endpoint") val azureEndpoint: String? = null,
    @SerialName("azure_version") val azureVersion: String? = null,
    @SerialName("azure_deployment") val azureDeployment: String? = null,
)
