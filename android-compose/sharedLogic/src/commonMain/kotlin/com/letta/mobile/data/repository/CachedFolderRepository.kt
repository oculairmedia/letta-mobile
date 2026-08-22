package com.letta.mobile.data.repository

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderAgentsListParams
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderFileUploadParams
import com.letta.mobile.data.model.FolderFilesListParams
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.FolderListParams
import com.letta.mobile.data.model.FolderPassagesListParams
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.api.FolderIrohSource
import com.letta.mobile.data.repository.api.FolderRemoteSource
import com.letta.mobile.data.repository.api.IFolderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 5f: platform-neutral cached folder repository. Android supplies HTTP
 * [FolderRemoteSource] and optional [FolderIrohSource] for list refreshes.
 */
open class CachedFolderRepository(
    private val remote: FolderRemoteSource,
    private val irohFolderSource: FolderIrohSource? = null,
) : IFolderRepository {
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    override val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    override suspend fun refreshFolders(name: String?) {
        val irohSource = irohFolderSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _folders.value = irohSource.listFolders(name)
            return
        }
        val filter = FolderListParams(name = name)
        _folders.value = exhaustCursorPages(
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, after ->
                remote.listFolders(
                    filter.copy(limit = limit, after = after),
                )
            },
            itemKey = { folder -> folder.id.value },
        )
    }

    override suspend fun countFolders(): Int = remote.countFolders()

    override suspend fun getFolder(folderId: FolderId): Folder {
        return remote.retrieveFolder(folderId.value)
    }

    override suspend fun getFolderMetadata(includeDetailedPerSourceMetadata: Boolean): OrganizationSourcesStats {
        return remote.retrieveFolderMetadata(includeDetailedPerSourceMetadata)
    }

    override suspend fun createFolder(params: FolderCreateParams): Folder {
        val folder = remote.createFolder(params)
        upsertFolder(folder)
        return folder
    }

    override suspend fun updateFolder(folderId: FolderId, params: FolderUpdateParams): Folder {
        val folder = remote.updateFolder(folderId.value, params)
        upsertFolder(folder)
        return folder
    }

    override suspend fun deleteFolder(folderId: FolderId) {
        remote.deleteFolder(folderId.value)
        _folders.update { current -> current.filterNot { it.id == folderId } }
    }

    override suspend fun uploadFileToFolder(params: FolderFileUploadParams): FileMetadata {
        return remote.uploadFileToFolder(params)
    }

    override suspend fun listAgentsForFolder(folderId: FolderId): List<String> =
        exhaustFolderChildPages(
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetchPage = { limit, after ->
                remote.listAgentsForFolder(FolderAgentsListParams(folderId = folderId, limit = limit, after = after))
            },
            itemKey = { agentId -> agentId },
        )

    override suspend fun listFolderPassages(folderId: FolderId): List<Passage> =
        exhaustFolderChildPages(
            maxPages = PaginationConstants.BOUNDED_MAX_PAGES,
            fetchPage = { limit, after ->
                remote.listFolderPassages(FolderPassagesListParams(folderId = folderId, limit = limit, after = after))
            },
            itemKey = { passage -> passage.id },
        )

    override suspend fun listFolderFiles(folderId: FolderId, includeContent: Boolean): List<FileMetadata> =
        exhaustFolderChildPages(
            maxPages = PaginationConstants.BOUNDED_MAX_PAGES,
            fetchPage = { limit, after ->
                remote.listFolderFiles(
                    FolderFilesListParams(folderId = folderId, includeContent = includeContent, limit = limit, after = after),
                )
            },
            itemKey = { file -> file.id },
        )

    override suspend fun deleteFileFromFolder(folderId: FolderId, fileId: String) {
        remote.deleteFileFromFolder(folderId.value, fileId)
    }

    private suspend fun <T> exhaustFolderChildPages(
        maxPages: Int,
        fetchPage: suspend (limit: Int, after: String?) -> List<T>,
        itemKey: (T) -> String,
    ): List<T> = exhaustCursorPages(maxPages = maxPages, fetch = fetchPage, itemKey = itemKey)

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
