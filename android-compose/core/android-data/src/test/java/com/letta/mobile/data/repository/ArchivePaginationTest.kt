package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ArchiveApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveAgentsListParams
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveListParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Phase 2.2 (data-efficiency-audit Q3): focused pagination tests for the
 * repositories whose `limit = 1000` calls were replaced with [exhaustPages]
 * / [exhaustCursorPages]. Each test exercises the "exactly two pages" case
 * from the audit doc: the API returns a full first page and a short second
 * page, and we assert both pages are fetched with the right cursors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ArchivePaginationTest {

    @Test
    fun `refreshArchives fetches both pages when API returns exactly two`() = runTest {
        // 75 archives, default pageSize = 50 -> first page full, second page short.
        val archives = (1..75).map { Archive(id = "archive-$it", name = "Archive $it") }
        val api = PaginatingArchiveApi(archives)
        val repo = ArchiveRepository(api)

        repo.refreshArchives()

        assertEquals(75, repo.archives.value.size)
        assertEquals(listOf(null, "archive-50"), api.observedAfters)
        assertEquals(listOf(50, 50), api.observedLimits)
    }

    @Test
    fun `listAgentsForArchive fetches both pages when API returns exactly two`() = runTest {
        val agents = (1..60).map { Agent(id = AgentId("agent-$it"), name = "Agent $it") }
        val api = PaginatingArchiveApi(archives = emptyList(), agents = agents)
        val repo = ArchiveRepository(api)

        val result = repo.listAgentsForArchive("archive-1")

        assertEquals(60, result.size)
        assertEquals(2, api.observedAftersForAgents.size)
        assertEquals(null, api.observedAftersForAgents[0])
        assertEquals("agent-50", api.observedAftersForAgents[1])
    }

    private class PaginatingArchiveApi(
        private val archives: List<Archive>,
        private val agents: List<Agent> = listOf(Agent(id = AgentId("agent-1"), name = "Agent 1")),
    ) : ArchiveApi(mockk(relaxed = true)) {
        val observedAfters = mutableListOf<String?>()
        val observedLimits = mutableListOf<Int?>()
        val observedAftersForAgents = mutableListOf<String?>()

        override suspend fun listArchives(params: ArchiveListParams): List<Archive> {
            observedAfters += params.after
            observedLimits += params.limit
            val pageSize = params.limit ?: 50
            val start = params.after?.let { id ->
                archives.indexOfFirst { it.id == id }.let { if (it < 0) archives.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(archives.size)
            return archives.subList(start, end)
        }

        override suspend fun listAgentsForArchive(params: ArchiveAgentsListParams): List<Agent> {
            observedAftersForAgents += params.after
            val pageSize = params.limit ?: 50
            val start = after?.let { id ->
                agents.indexOfFirst { it.id.value == id }.let { if (it < 0) agents.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(agents.size)
            return agents.subList(start, end)
        }

        override suspend fun retrieveArchive(archiveId: String): Archive =
            throw ApiException(404, "not used")

        override suspend fun createArchive(params: ArchiveCreateParams): Archive =
            throw ApiException(500, "not used")

        override suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive =
            throw ApiException(500, "not used")

        override suspend fun deleteArchive(archiveId: String): Archive =
            throw ApiException(500, "not used")

        override suspend fun deletePassageFromArchive(archiveId: String, passageId: String) = Unit
    }
}