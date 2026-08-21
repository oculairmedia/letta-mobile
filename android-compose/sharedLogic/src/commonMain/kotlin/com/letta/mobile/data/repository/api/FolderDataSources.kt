package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import io.ktor.http.ContentType

/**
 * Remote HTTP (or equivalent) folder admin surface used by
 * [com.letta.mobile.data.repository.CachedFolderRepository].
 * Platform modules supply Ktor/[FolderApi] bindings; Iroh list traffic goes
 * through [FolderIrohSource].
 */
interface FolderRemoteSource {
    suspend fun countFolders(): Int
    suspend fun retrieveFolder(folderId: String): Folder
    suspend fun retrieveFolderMetadata(includeDetailedPerSourceMetadata: Boolean = false): OrganizationSourcesStats
    suspend fun listFolders(
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
        name: String? = null,
    ): List<Folder>

    suspend fun createFolder(params: FolderCreateParams): Folder
    suspend fun updateFolder(folderId: String, params: FolderUpdateParams): Folder
    suspend fun deleteFolder(folderId: String)
    suspend fun uploadFileToFolder(
        folderId: String,
        fileName: String,
        fileBytes: ByteArray,
        duplicateHandling: String? = null,
        customName: String? = null,
        contentType: ContentType = ContentType.Application.OctetStream,
    ): FileMetadata

    suspend fun listAgentsForFolder(
        folderId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<String>

    suspend fun listFolderPassages(
        folderId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<Passage>

    suspend fun listFolderFiles(
        folderId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
        includeContent: Boolean? = null,
    ): List<FileMetadata>

    suspend fun deleteFileFromFolder(folderId: String, fileId: String)
}

/**
 * Iroh admin_rpc folder list surface. Implemented by
 * [com.letta.mobile.data.repository.IrohAdminRpcFolderSource].
 * Mutating folder ops still go through [FolderRemoteSource] until Iroh handlers exist.
 */
interface FolderIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listFolders(name: String? = null): List<Folder>
}
