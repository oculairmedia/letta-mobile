package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Block(
    val id: String,
    val label: String? = null,
    val value: String,
    val limit: Int? = null,
    val description: String? = null,
    @SerialName("is_template") val isTemplate: Boolean? = null,
    @SerialName("read_only") val readOnly: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class BlockCreateParams(
    val label: String,
    val value: String,
    val limit: Int? = null,
    val description: String? = null,
)

@Serializable
data class BlockUpdateParams(
    val value: String? = null,
    val limit: Int? = null,
    val description: String? = null,
)
