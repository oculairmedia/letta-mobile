package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.FolderApi
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
import io.mockk.mockk

class FakeFolderApi : FolderApi(mockk(relaxed = true)) {
    var folders = mutableListOf<Folder>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listFolders(params: FolderListParams): List<Folder> {
        calls.add("listFolders")
        if (shouldFail) throw ApiException(500, "Server error")
        return folders.filter { params.name == null || it.name == params.name }
    }

    override suspend fun countFolders(): Int {
        calls.add("countFolders")
        if (shouldFail) throw ApiException(500, "Server error")
        return folders.size
    }

    override suspend fun retrieveFolder(folderId: String): Folder {
        calls.add("retrieveFolder:$folderId")
        if (shouldFail) throw ApiException(500, "Server error")
        return folders.firstOrNull { it.id.value == folderId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun retrieveFolderMetadata(includeDetailedPerSourceMetadata: Boolean): OrganizationSourcesStats {
        calls.add("retrieveFolderMetadata:$includeDetailedPerSourceMetadata")
        if (shouldFail) throw ApiException(500, "Server error")
        return OrganizationSourcesStats(totalSources = folders.size)
    }

    override suspend fun createFolder(params: FolderCreateParams): Folder {
        calls.add("createFolder:${params.name}")
        if (shouldFail) throw ApiException(500, "Server error")
        val folder = Folder(id = FolderId("source-${folders.size + 1}"), name = params.name, description = params.description, instructions = params.instructions, embeddingConfig = params.embeddingConfig)
        folders.add(folder)
        return folder
    }

    override suspend fun updateFolder(folderId: String, params: FolderUpdateParams): Folder {
        calls.add("updateFolder:$folderId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = folders.indexOfFirst { it.id.value == folderId }
        if (index < 0) throw ApiException(404, "Not found")
        val updated = folders[index].copy(
            name = params.name ?: folders[index].name,
            description = params.description ?: folders[index].description,
            instructions = params.instructions ?: folders[index].instructions,
        )
        folders[index] = updated
        return updated
    }

    override suspend fun deleteFolder(folderId: String) {
        calls.add("deleteFolder:$folderId")
        if (shouldFail) throw ApiException(500, "Server error")
        folders.removeAll { it.id.value == folderId }
    }

    override suspend fun uploadFileToFolder(params: FolderFileUploadParams): FileMetadata {
        calls.add("uploadFileToFolder:${params.folderId.value}:${params.fileName}")
        if (shouldFail) throw ApiException(500, "Server error")
        return FileMetadata(
            id = "file-1",
            sourceId = params.folderId,
            fileName = params.customName ?: params.fileName,
        )
    }

    override suspend fun listAgentsForFolder(params: FolderAgentsListParams): List<String> {
        calls.add("listAgentsForFolder:${params.folderId.value}")
        if (shouldFail) throw ApiException(500, "Server error")
        return listOf("agent-1")
    }

    override suspend fun listFolderPassages(params: FolderPassagesListParams): List<Passage> {
        calls.add("listFolderPassages:${params.folderId.value}")
        if (shouldFail) throw ApiException(500, "Server error")
        return listOf(Passage(id = "passage-1", text = "text", sourceId = params.folderId.value))
    }

    override suspend fun listFolderFiles(params: FolderFilesListParams): List<FileMetadata> {
        calls.add("listFolderFiles:${params.folderId.value}")
        if (shouldFail) throw ApiException(500, "Server error")
        return listOf(FileMetadata(id = "file-1", sourceId = params.folderId, fileName = "doc.txt"))
    }

    override suspend fun deleteFileFromFolder(folderId: String, fileId: String) {
        calls.add("deleteFileFromFolder:$folderId:$fileId")
        if (shouldFail) throw ApiException(500, "Server error")
    }
}
