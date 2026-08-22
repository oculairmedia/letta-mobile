package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderAgentsListParams
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderFileUploadParams
import com.letta.mobile.data.model.FolderFilesListParams
import com.letta.mobile.data.model.FolderListParams
import com.letta.mobile.data.model.FolderPassagesListParams
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage

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
    suspend fun listFolders(params: FolderListParams = FolderListParams()): List<Folder>

    suspend fun createFolder(params: FolderCreateParams): Folder
    suspend fun updateFolder(folderId: String, params: FolderUpdateParams): Folder
    suspend fun deleteFolder(folderId: String)
    suspend fun uploadFileToFolder(params: FolderFileUploadParams): FileMetadata

    suspend fun listAgentsForFolder(params: FolderAgentsListParams): List<String>

    suspend fun listFolderPassages(params: FolderPassagesListParams): List<Passage>

    suspend fun listFolderFiles(params: FolderFilesListParams): List<FileMetadata>

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
