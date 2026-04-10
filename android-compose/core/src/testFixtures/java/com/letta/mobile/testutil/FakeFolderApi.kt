package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.FolderApi
import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import io.ktor.http.ContentType
import io.mockk.mockk

class FakeFolderApi : FolderApi(mockk(relaxed = true)) {
    var folders = mutableListOf<Folder>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listFolders(before: String?, after: String?, limit: Int?, order: String?, name: String?): List<Folder> {
        calls.add("listFolders")
        if (shouldFail) throw ApiException(500, "Server error")
        return folders.filter { name == null || it.name == name }
    }

    override suspend fun countFolders(): Int {
        calls.add("countFolders")
        if (shouldFail) throw ApiException(500, "Server error")
        return folders.size
    }

    override suspend fun retrieveFolder(folderId: String): Folder {
        calls.add("retrieveFolder:$folderId")
        if (shouldFail) throw ApiException(500, "Server error")
        return folders.firstOrNull { it.id == folderId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun retrieveFolderMetadata(includeDetailedPerSourceMetadata: Boolean): OrganizationSourcesStats {
        calls.add("retrieveFolderMetadata:$includeDetailedPerSourceMetadata")
        if (shouldFail) throw ApiException(500, "Server error")
        return OrganizationSourcesStats(totalSources = folders.size)
    }

    override suspend fun createFolder(params: FolderCreateParams): Folder {
        calls.add("createFolder:${params.name}")
        if (shouldFail) throw ApiException(500, "Server error")
        val folder = Folder(id = "source-${folders.size + 1}", name = params.name, description = params.description, instructions = params.instructions, embeddingConfig = params.embeddingConfig)
        folders.add(folder)
        return folder
    }

    override suspend fun updateFolder(folderId: String, params: FolderUpdateParams): Folder {
        calls.add("updateFolder:$folderId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = folders.indexOfFirst { it.id == folderId }
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
        folders.removeAll { it.id == folderId }
    }

    override suspend fun uploadFileToFolder(
        folderId: String,
        fileName: String,
        fileBytes: ByteArray,
        duplicateHandling: String?,
        customName: String?,
        contentType: ContentType,
    ): FileMetadata {
        calls.add("uploadFileToFolder:$folderId:$fileName")
        if (shouldFail) throw ApiException(500, "Server error")
        return FileMetadata(id = "file-1", sourceId = folderId, fileName = customName ?: fileName)
    }

    override suspend fun listAgentsForFolder(folderId: String, limit: Int?, before: String?, after: String?, order: String?): List<String> {
        calls.add("listAgentsForFolder:$folderId")
        if (shouldFail) throw ApiException(500, "Server error")
        return listOf("agent-1")
    }

    override suspend fun listFolderPassages(folderId: String, limit: Int?, before: String?, after: String?, order: String?): List<Passage> {
        calls.add("listFolderPassages:$folderId")
        if (shouldFail) throw ApiException(500, "Server error")
        return listOf(Passage(id = "passage-1", text = "text", sourceId = folderId))
    }

    override suspend fun listFolderFiles(folderId: String, limit: Int?, before: String?, after: String?, order: String?, includeContent: Boolean?): List<FileMetadata> {
        calls.add("listFolderFiles:$folderId")
        if (shouldFail) throw ApiException(500, "Server error")
        return listOf(FileMetadata(id = "file-1", sourceId = folderId, fileName = "doc.txt"))
    }

    override suspend fun deleteFileFromFolder(folderId: String, fileId: String) {
        calls.add("deleteFileFromFolder:$folderId:$fileId")
        if (shouldFail) throw ApiException(500, "Server error")
    }
}
