package com.letta.mobile.data.session

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.api.IFolderRepository
import io.ktor.http.ContentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
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
    proxyScope: CoroutineScope,
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

    override suspend fun refreshFolders(name: String?) = current.refreshFolders(name)

    override suspend fun countFolders(): Int = current.countFolders()

    override suspend fun getFolder(folderId: String): Folder = current.getFolder(folderId)

    override suspend fun getFolderMetadata(includeDetailedPerSourceMetadata: Boolean): OrganizationSourcesStats =
        current.getFolderMetadata(includeDetailedPerSourceMetadata)

    override suspend fun createFolder(params: FolderCreateParams): Folder = current.createFolder(params)

    override suspend fun updateFolder(folderId: String, params: FolderUpdateParams): Folder =
        current.updateFolder(folderId, params)

    override suspend fun deleteFolder(folderId: String) = current.deleteFolder(folderId)

    override suspend fun uploadFileToFolder(
        folderId: String,
        fileName: String,
        fileBytes: ByteArray,
        duplicateHandling: String?,
        customName: String?,
        contentType: ContentType,
    ): FileMetadata = current.uploadFileToFolder(
        folderId = folderId,
        fileName = fileName,
        fileBytes = fileBytes,
        duplicateHandling = duplicateHandling,
        customName = customName,
        contentType = contentType,
    )

    override suspend fun listAgentsForFolder(folderId: String): List<String> =
        current.listAgentsForFolder(folderId)

    override suspend fun listFolderPassages(folderId: String): List<Passage> =
        current.listFolderPassages(folderId)

    override suspend fun listFolderFiles(folderId: String, includeContent: Boolean): List<FileMetadata> =
        current.listFolderFiles(folderId, includeContent)

    override suspend fun deleteFileFromFolder(folderId: String, fileId: String) =
        current.deleteFileFromFolder(folderId, fileId)
}
