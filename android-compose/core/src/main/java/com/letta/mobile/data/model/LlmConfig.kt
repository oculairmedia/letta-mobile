package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LlmConfig(
    @SerialName("context_window") val contextWindow: Int? = null,
    val model: String? = null,
)
