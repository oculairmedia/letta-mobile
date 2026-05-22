package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.StateFlow

interface IFolderRepository {
    val folders: StateFlow<List<Folder>>
    suspend fun refreshFolders(name: String? = null)
    suspend fun countFolders(): Int
    suspend fun getFolder(folderId: String): Folder
    suspend fun getFolderMetadata(includeDetailedPerSourceMetadata: Boolean = false): OrganizationSourcesStats
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
    suspend fun listAgentsForFolder(folderId: String): List<String>
    suspend fun listFolderPassages(folderId: String): List<Passage>
    suspend fun listFolderFiles(folderId: String, includeContent: Boolean = false): List<FileMetadata>
    suspend fun deleteFileFromFolder(folderId: String, fileId: String)
}
