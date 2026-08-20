package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ArchiveApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import io.mockk.mockk

class FakeArchiveApi : ArchiveApi(mockk(relaxed = true)) {
    var archives = mutableListOf<Archive>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listArchives(
        before: String?,
        after: String?,
        limit: Int?,
        order: String?,
        name: String?,
        agentId: String?,
    ): List<Archive> {
        calls.add("listArchives")
        if (shouldFail) throw ApiException(500, "Server error")
        return archives.filter { name == null || it.name == name }
    }

    override suspend fun retrieveArchive(archiveId: String): Archive {
        calls.add("retrieveArchive:$archiveId")
        if (shouldFail) throw ApiException(500, "Server error")
        return archives.firstOrNull { it.id == archiveId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun createArchive(params: ArchiveCreateParams): Archive {
        calls.add("createArchive:${params.name}")
        if (shouldFail) throw ApiException(500, "Server error")
        val archive = Archive(id = "archive-${archives.size + 1}", name = params.name, description = params.description, embeddingConfig = params.embeddingConfig)
        archives.add(archive)
        return archive
    }

    override suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive {
        calls.add("updateArchive:$archiveId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = archives.indexOfFirst { it.id == archiveId }
        if (index < 0) throw ApiException(404, "Not found")
        val updated = archives[index].copy(name = params.name ?: archives[index].name, description = params.description ?: archives[index].description)
        archives[index] = updated
        return updated
    }

    override suspend fun deleteArchive(archiveId: String): Archive {
        calls.add("deleteArchive:$archiveId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = archives.indexOfFirst { it.id == archiveId }
        if (index < 0) throw ApiException(404, "Not found")
        return archives.removeAt(index)
    }

    override suspend fun listAgentsForArchive(
        archiveId: String,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
    ): List<Agent> {
        calls.add("listAgentsForArchive:$archiveId")
        if (shouldFail) throw ApiException(500, "Server error")
        return listOf(TestData.agent(id = "agent-1"))
    }

    override suspend fun deletePassageFromArchive(archiveId: String, passageId: String) {
        calls.add("deletePassageFromArchive:$archiveId:$passageId")
        if (shouldFail) throw ApiException(500, "Server error")
    }
}
