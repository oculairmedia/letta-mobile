package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Block(
    val id: BlockId,
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

/** Filter + offset pagination for `GET /v1/blocks` and `block.list` admin_rpc. */
@Serializable
data class BlockListParams(
    val label: String? = null,
    @SerialName("is_template") val isTemplate: Boolean? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

/** Cursor pagination for `GET /v1/blocks/{block_id}/agents`. */
data class BlockAgentsListParams(
    val blockId: BlockId,
    val limit: Int? = null,
    val before: String? = null,
    val after: String? = null,
    val order: String? = null,
)
