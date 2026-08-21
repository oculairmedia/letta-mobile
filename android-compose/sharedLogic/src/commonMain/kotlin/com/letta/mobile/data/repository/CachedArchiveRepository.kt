package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import com.letta.mobile.data.repository.api.ArchiveIrohSource
import com.letta.mobile.data.repository.api.ArchiveRemoteSource
import com.letta.mobile.data.repository.api.IArchiveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 5h: platform-neutral cached archive repository. Android supplies HTTP
 * [ArchiveRemoteSource] and optional [ArchiveIrohSource] for list refreshes.
 */
open class CachedArchiveRepository(
    private val remote: ArchiveRemoteSource,
    private val irohArchiveSource: ArchiveIrohSource? = null,
) : IArchiveRepository {
    private val _archives = MutableStateFlow<List<Archive>>(emptyList())
    override val archives: StateFlow<List<Archive>> = _archives.asStateFlow()

    override suspend fun refreshArchives(name: String?, agentId: String?) {
        val irohSource = irohArchiveSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _archives.value = irohSource.listArchives()
            return
        }
        _archives.value = exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, after ->
                remote.listArchives(
                    limit = limit,
                    before = null,
                    after = after,
                    order = null,
                    name = name,
                    agentId = agentId,
                )
            },
            extractCursor = { archive -> archive.id },
            dedupKey = { archive -> archive.id },
        )
    }

    override suspend fun getArchive(archiveId: String): Archive {
        return remote.retrieveArchive(archiveId)
    }

    override suspend fun createArchive(params: ArchiveCreateParams): Archive {
        val archive = remote.createArchive(params)
        upsertArchive(archive)
        return archive
    }

    override suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive {
        val archive = remote.updateArchive(archiveId, params)
        upsertArchive(archive)
        return archive
    }

    override suspend fun deleteArchive(archiveId: String): Archive {
        val archive = remote.deleteArchive(archiveId)
        _archives.update { current -> current.filterNot { it.id == archiveId } }
        return archive
    }

    override suspend fun listAgentsForArchive(archiveId: String): List<Agent> {
        return exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, after ->
                remote.listAgentsForArchive(
                    archiveId = archiveId,
                    limit = limit,
                    before = null,
                    after = after,
                    order = null,
                )
            },
            extractCursor = { agent -> agent.id.value },
            dedupKey = { agent -> agent.id.value },
        )
    }

    override suspend fun deletePassageFromArchive(archiveId: String, passageId: String) {
        remote.deletePassageFromArchive(archiveId, passageId)
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
