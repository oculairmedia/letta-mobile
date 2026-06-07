package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.StateFlow

interface IFolderRepository {
    val folders: StateFlow<List<Folder>>
    suspend fun refreshFolders(name: String? = null)
    suspend fun countFolders(): Int
    suspend fun getFolder(folderId: FolderId): Folder
    suspend fun getFolderMetadata(includeDetailedPerSourceMetadata: Boolean = false): OrganizationSourcesStats
    suspend fun createFolder(params: FolderCreateParams): Folder
    suspend fun updateFolder(folderId: FolderId, params: FolderUpdateParams): Folder
    suspend fun deleteFolder(folderId: FolderId)
    suspend fun uploadFileToFolder(
        folderId: FolderId,
        fileName: String,
        fileBytes: ByteArray,
        duplicateHandling: String? = null,
        customName: String? = null,
        contentType: ContentType = ContentType.Application.OctetStream,
    ): FileMetadata
    suspend fun listAgentsForFolder(folderId: FolderId): List<String>
    suspend fun listFolderPassages(folderId: FolderId): List<Passage>
    suspend fun listFolderFiles(folderId: FolderId, includeContent: Boolean = false): List<FileMetadata>
    suspend fun deleteFileFromFolder(folderId: FolderId, fileId: String)
}
