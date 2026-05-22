package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ArchiveApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import com.letta.mobile.data.repository.api.IArchiveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

open class ArchiveRepository(
    private val archiveApi: ArchiveApi,
) : IArchiveRepository {
    private val _archives = MutableStateFlow<List<Archive>>(emptyList())
    override val archives: StateFlow<List<Archive>> = _archives.asStateFlow()

    override suspend fun refreshArchives(name: String?, agentId: String?) {
        _archives.value = archiveApi.listArchives(limit = 1000, name = name, agentId = agentId)
    }

    override suspend fun getArchive(archiveId: String): Archive {
        return archiveApi.retrieveArchive(archiveId)
    }

    override suspend fun createArchive(params: ArchiveCreateParams): Archive {
        val archive = archiveApi.createArchive(params)
        upsertArchive(archive)
        return archive
    }

    override suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive {
        val archive = archiveApi.updateArchive(archiveId, params)
        upsertArchive(archive)
        return archive
    }

    override suspend fun deleteArchive(archiveId: String): Archive {
        val archive = archiveApi.deleteArchive(archiveId)
        _archives.update { current -> current.filterNot { it.id == archiveId } }
        return archive
    }

    override suspend fun listAgentsForArchive(archiveId: String): List<Agent> {
        return archiveApi.listAgentsForArchive(archiveId = archiveId, limit = 1000)
    }

    override suspend fun deletePassageFromArchive(archiveId: String, passageId: String) {
        archiveApi.deletePassageFromArchive(archiveId, passageId)
    }

    private fun upsertArchive(archive: Archive) {
        _archives.update { current ->
            val index = current.indexOfFirst { it.id == archive.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = archive }
            } else {
                current + archive
            }
        }
    }
}
