package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Archive(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("vector_db_provider") val vectorDbProvider: String? = null,
    @SerialName("embedding_config") val embeddingConfig: EmbeddingConfig? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ArchiveCreateParams(
    val name: String,
    @SerialName("embedding_config") val embeddingConfig: EmbeddingConfig,
    val description: String? = null,
)

@Serializable
data class ArchiveUpdateParams(
    val name: String? = null,
    val description: String? = null,
)

/** Cursor + filter params for `GET /v1/archives`. */
data class ArchiveListParams(
    val before: String? = null,
    val after: String? = null,
    val limit: Int? = null,
    val order: String? = null,
    val name: String? = null,
    val agentId: String? = null,
)

/** Cursor pagination for `GET /v1/archives/{archive_id}/agents`. */
data class ArchiveAgentsListParams(
    val archiveId: String,
    val limit: Int? = null,
    val before: String? = null,
    val after: String? = null,
    val order: String? = null,
)
