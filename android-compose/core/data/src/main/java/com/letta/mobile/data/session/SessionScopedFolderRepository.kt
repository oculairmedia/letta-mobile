package com.letta.mobile.data.session

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.api.IFolderRepository
import io.ktor.http.ContentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedFolderRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedFolderRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IFolderRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedFolderRepositoryScope(),
    )

    private val _folders = MutableStateFlow(sessionManager.current.folderRepository.folders.value)
    override val folders: StateFlow<List<Folder>> = _folders

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.folderRepository.folders }
            .onEach { _folders.value = it }
            .launchIn(proxyScope)
    }

    private val current: IFolderRepository
        get() = sessionManager.current.folderRepository

    override suspend fun refreshFolders(name: String?) = sessionManager.withCurrentSession { it.folderRepository.refreshFolders(name) }

    override suspend fun countFolders(): Int = sessionManager.withCurrentSession { it.folderRepository.countFolders() }

    override suspend fun getFolder(folderId: FolderId): Folder = sessionManager.withCurrentSession { it.folderRepository.getFolder(folderId) }

    override suspend fun getFolderMetadata(includeDetailedPerSourceMetadata: Boolean): OrganizationSourcesStats =
        sessionManager.withCurrentSession { it.folderRepository.getFolderMetadata(includeDetailedPerSourceMetadata) }

    override suspend fun createFolder(params: FolderCreateParams): Folder = sessionManager.withCurrentSession { it.folderRepository.createFolder(params) }

    override suspend fun updateFolder(folderId: FolderId, params: FolderUpdateParams): Folder =
        sessionManager.withCurrentSession { it.folderRepository.updateFolder(folderId, params) }

    override suspend fun deleteFolder(folderId: FolderId) = sessionManager.withCurrentSession { it.folderRepository.deleteFolder(folderId) }

    override suspend fun uploadFileToFolder(
        folderId: FolderId,
        fileName: String,
        fileBytes: ByteArray,
        duplicateHandling: String?,
        customName: String?,
        contentType: ContentType,
    ): FileMetadata = sessionManager.withCurrentSession { it.folderRepository.uploadFileToFolder(
        folderId = folderId,
        fileName = fileName,
        fileBytes = fileBytes,
        duplicateHandling = duplicateHandling,
        customName = customName,
        contentType = contentType,
    ) }

    override suspend fun listAgentsForFolder(folderId: FolderId): List<String> =
        sessionManager.withCurrentSession { it.folderRepository.listAgentsForFolder(folderId) }

    override suspend fun listFolderPassages(folderId: FolderId): List<Passage> =
        sessionManager.withCurrentSession { it.folderRepository.listFolderPassages(folderId) }

    override suspend fun listFolderFiles(folderId: FolderId, includeContent: Boolean): List<FileMetadata> =
        sessionManager.withCurrentSession { it.folderRepository.listFolderFiles(folderId, includeContent) }

    override suspend fun deleteFileFromFolder(folderId: FolderId, fileId: String) =
        sessionManager.withCurrentSession { it.folderRepository.deleteFileFromFolder(folderId, fileId) }

    fun close() { proxyScope.cancel() }
}
