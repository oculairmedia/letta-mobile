package com.letta.mobile.data.model

import io.ktor.http.ContentType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Folder(
    val id: FolderId,
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

/** Cursor pagination for `GET /v1/folders/`. */
data class FolderListParams(
    val before: String? = null,
    val after: String? = null,
    val limit: Int? = null,
    val order: String? = null,
    val name: String? = null,
)

/** Cursor pagination for folder-scoped agent lists. */
data class FolderAgentsListParams(
    val folderId: FolderId,
    val limit: Int? = null,
    val before: String? = null,
    val after: String? = null,
    val order: String? = null,
)

/** Cursor pagination for folder-scoped passage lists. */
data class FolderPassagesListParams(
    val folderId: FolderId,
    val limit: Int? = null,
    val before: String? = null,
    val after: String? = null,
    val order: String? = null,
)

/** Cursor pagination for folder-scoped file lists. */
data class FolderFilesListParams(
    val folderId: FolderId,
    val limit: Int? = null,
    val before: String? = null,
    val after: String? = null,
    val order: String? = null,
    val includeContent: Boolean? = null,
)

/** Multipart upload payload for `POST /v1/folders/{folder_id}/upload`. */
data class FolderFileUploadParams(
    val folderId: FolderId,
    val fileName: String,
    val fileBytes: ByteArray,
    val duplicateHandling: String? = null,
    val customName: String? = null,
    val contentType: ContentType = ContentType.Application.OctetStream,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as FolderFileUploadParams
        if (folderId != other.folderId) return false
        if (fileName != other.fileName) return false
        if (!fileBytes.contentEquals(other.fileBytes)) return false
        if (duplicateHandling != other.duplicateHandling) return false
        if (customName != other.customName) return false
        if (contentType != other.contentType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = folderId.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + fileBytes.contentHashCode()
        result = 31 * result + (duplicateHandling?.hashCode() ?: 0)
        result = 31 * result + (customName?.hashCode() ?: 0)
        result = 31 * result + contentType.hashCode()
        return result
    }
}

@Serializable
data class FileMetadata(
    val id: String,
    @SerialName("source_id") val sourceId: FolderId,
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
    @SerialName("source_id") val sourceId: FolderId,
    @SerialName("source_name") val sourceName: String,
    val fileCount: Int = 0,
    val totalSize: Int = 0,
    @Serializable(with = ImmutableListSerializer::class) val files: ImmutableList<FileStats> = persistentListOf(),
)

@Serializable
data class OrganizationSourcesStats(
    @SerialName("total_sources") val totalSources: Int = 0,
    @SerialName("total_files") val totalFiles: Int = 0,
    @SerialName("total_size") val totalSize: Int = 0,
    val sources: List<SourceStats> = emptyList(),
)
