package com.letta.mobile.data.repository

import com.letta.mobile.data.api.FolderApi
import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.api.IFolderRepository
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FolderRepository(
    private val folderApi: FolderApi,
) : IFolderRepository {
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    override val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    override suspend fun refreshFolders(name: String?) {
        _folders.value = folderApi.listFolders(limit = 1000, name = name)
    }

    override suspend fun countFolders(): Int = folderApi.countFolders()

    override suspend fun getFolder(folderId: FolderId): Folder {
        return folderApi.retrieveFolder(folderId.value)
    }

    override suspend fun getFolderMetadata(includeDetailedPerSourceMetadata: Boolean): OrganizationSourcesStats {
        return folderApi.retrieveFolderMetadata(includeDetailedPerSourceMetadata)
    }

    override suspend fun createFolder(params: FolderCreateParams): Folder {
        val folder = folderApi.createFolder(params)
        upsertFolder(folder)
        return folder
    }

    override suspend fun updateFolder(folderId: FolderId, params: FolderUpdateParams): Folder {
        val folder = folderApi.updateFolder(folderId.value, params)
        upsertFolder(folder)
        return folder
    }

    override suspend fun deleteFolder(folderId: FolderId) {
        folderApi.deleteFolder(folderId.value)
        _folders.update { current -> current.filterNot { it.id == folderId } }
    }

    override suspend fun uploadFileToFolder(
        folderId: FolderId,
        fileName: String,
        fileBytes: ByteArray,
        duplicateHandling: String?,
        customName: String?,
        contentType: ContentType,
    ): FileMetadata {
        return folderApi.uploadFileToFolder(folderId.value, fileName, fileBytes, duplicateHandling, customName, contentType)
    }

    override suspend fun listAgentsForFolder(folderId: FolderId): List<String> {
        return folderApi.listAgentsForFolder(folderId = folderId.value, limit = 1000)
    }

    override suspend fun listFolderPassages(folderId: FolderId): List<Passage> {
        return folderApi.listFolderPassages(folderId = folderId.value, limit = 1000)
    }

    override suspend fun listFolderFiles(folderId: FolderId, includeContent: Boolean): List<FileMetadata> {
        return folderApi.listFolderFiles(folderId = folderId.value, limit = 1000, includeContent = includeContent)
    }

    override suspend fun deleteFileFromFolder(folderId: FolderId, fileId: String) {
        folderApi.deleteFileFromFolder(folderId.value, fileId)
    }

    private fun upsertFolder(folder: Folder) {
        _folders.update { current ->
            val index = current.indexOfFirst { it.id == folder.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = folder }
            } else {
                current + folder
            }
        }
    }
}
