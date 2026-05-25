package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Folder(
    val id: String,
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    @SerialName("embedding_config") val embeddingConfig: EmbeddingConfig? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("vector_db_provider") val vectorDbProvider: String? = null,
    @SerialName("created_by_id") val createdById: String? = null,
    @SerialName("last_updated_by_id") val lastUpdatedById: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class FolderCreateParams(
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val metadata: Map<String, JsonElement>? = null,
    val embedding: String? = null,
    @SerialName("embedding_chunk_size") val embeddingChunkSize: Int? = null,
    @SerialName("embedding_config") val embeddingConfig: EmbeddingConfig? = null,
)

@Serializable
data class FolderUpdateParams(
    val name: String? = null,
    val description: String? = null,
    val instructions: String? = null,
    val metadata: Map<String, JsonElement>? = null,
    @SerialName("embedding_config") val embeddingConfig: EmbeddingConfig? = null,
)

@Serializable
data class FileMetadata(
    val id: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("original_file_name") val originalFileName: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("file_type") val fileType: String? = null,
    @SerialName("file_size") val fileSize: Int? = null,
    @SerialName("file_creation_date") val fileCreationDate: String? = null,
    @SerialName("file_last_modified_date") val fileLastModifiedDate: String? = null,
    @SerialName("processing_status") val processingStatus: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("total_chunks") val totalChunks: Int? = null,
    @SerialName("chunks_embedded") val chunksEmbedded: Int? = null,
    val content: String? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class FileStats(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Int? = null,
)

@Serializable
data class SourceStats(
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_name") val sourceName: String,
    @SerialName("file_count") val fileCount: Int = 0,
    @SerialName("total_size") val totalSize: Int = 0,
    val files: List<FileStats> = emptyList(),
)

@Serializable
data class OrganizationSourcesStats(
    @SerialName("total_sources") val totalSources: Int = 0,
    @SerialName("total_files") val totalFiles: Int = 0,
    @SerialName("total_size") val totalSize: Int = 0,
    val sources: List<SourceStats> = emptyList(),
)
