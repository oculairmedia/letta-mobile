package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelSettings(
    val temperature: Double? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("provider_type") val providerType: String? = null,
)
