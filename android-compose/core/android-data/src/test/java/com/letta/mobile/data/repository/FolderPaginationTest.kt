package com.letta.mobile.data.repository

import com.letta.mobile.data.api.FolderApi
import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.Passage
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Phase 2.2 (data-efficiency-audit Q3): focused pagination tests for
 * [FolderRepository]. Exercises the four call sites that previously used
 * `limit = 1000`. The passages/files paths use a bounded
 * `maxPages = PaginationConstants.BOUNDED_MAX_PAGES` per the audit doc.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class FolderPaginationTest {

    @Test
    fun `refreshFolders fetches both pages when API returns exactly two`() = runTest {
        val folders = (1..75).map { Folder(id = FolderId("folder-$it"), name = "Folder $it") }
        val api = PaginatingFolderApi(folders = folders)
        val repo = FolderRepository(api)

        repo.refreshFolders()

        assertEquals(75, repo.folders.value.size)
        assertEquals(listOf<String?>(null, "folder-50"), api.observedAfters)
        assertEquals(listOf(50, 50), api.observedLimits)
    }

    @Test
    fun `listAgentsForFolder fetches both pages when API returns exactly two`() = runTest {
        val agents = (1..60).map { "agent-$it" }
        val api = PaginatingFolderApi(folders = emptyList(), agents = agents)
        val repo = FolderRepository(api)

        val result = repo.listAgentsForFolder(FolderId("folder-1"))

        assertEquals(60, result.size)
        assertEquals(2, api.observedAftersForAgents.size)
        assertEquals(null, api.observedAftersForAgents[0])
        assertEquals("agent-50", api.observedAftersForAgents[1])
    }

    @Test
    fun `listFolderPassages is bounded and fetches both pages when API returns exactly two`() = runTest {
        val passages = (1..60).map { Passage(id = "passage-$it", text = "text-$it", sourceId = "folder-1") }
        val api = PaginatingFolderApi(folders = emptyList(), passages = passages)
        val repo = FolderRepository(api)

        val result = repo.listFolderPassages(FolderId("folder-1"))

        assertEquals(60, result.size)
        assertEquals(2, api.observedAftersForPassages.size)
        assertEquals(null, api.observedAftersForPassages[0])
        assertEquals("passage-50", api.observedAftersForPassages[1])
    }

    @Test
    fun `listFolderFiles is bounded and fetches both pages when API returns exactly two`() = runTest {
        val files = (1..60).map { FileMetadata(id = "file-$it", sourceId = FolderId("folder-1"), fileName = "file-$it.txt") }
        val api = PaginatingFolderApi(folders = emptyList(), files = files)
        val repo = FolderRepository(api)

        val result = repo.listFolderFiles(FolderId("folder-1"), includeContent = false)

        assertEquals(60, result.size)
        assertEquals(2, api.observedAftersForFiles.size)
        assertEquals(null, api.observedAftersForFiles[0])
        assertEquals("file-50", api.observedAftersForFiles[1])
    }

    private class PaginatingFolderApi(
        private val folders: List<Folder>,
        private val agents: List<String> = listOf("agent-1"),
        private val passages: List<Passage> = listOf(Passage(id = "p1", text = "t", sourceId = "s")),
        private val files: List<FileMetadata> = listOf(FileMetadata(id = "f1", sourceId = FolderId("s"), fileName = "x")),
    ) : FolderApi(mockk(relaxed = true)) {
        val observedAfters = mutableListOf<String?>()
        val observedLimits = mutableListOf<Int?>()
        val observedAftersForAgents = mutableListOf<String?>()
        val observedAftersForPassages = mutableListOf<String?>()
        val observedAftersForFiles = mutableListOf<String?>()

        override suspend fun listFolders(
            before: String?,
            after: String?,
            limit: Int?,
            order: String?,
            name: String?,
        ): List<Folder> {
            observedAfters += after
            observedLimits += limit
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                folders.indexOfFirst { it.id.value == id }.let { if (it < 0) folders.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(folders.size)
            return folders.subList(start, end)
        }

        override suspend fun listAgentsForFolder(
            folderId: String,
            limit: Int?,
            before: String?,
            after: String?,
            order: String?,
        ): List<String> {
            observedAftersForAgents += after
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                agents.indexOfFirst { it == id }.let { if (it < 0) agents.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(agents.size)
            return agents.subList(start, end)
        }

        override suspend fun listFolderPassages(
            folderId: String,
            limit: Int?,
            before: String?,
            after: String?,
            order: String?,
        ): List<Passage> {
            observedAftersForPassages += after
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                passages.indexOfFirst { it.id == id }.let { if (it < 0) passages.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(passages.size)
            return passages.subList(start, end)
        }

        override suspend fun listFolderFiles(
            folderId: String,
            limit: Int?,
            before: String?,
            after: String?,
            order: String?,
            includeContent: Boolean?,
        ): List<FileMetadata> {
            observedAftersForFiles += after
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                files.indexOfFirst { it.id == id }.let { if (it < 0) files.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(files.size)
            return files.subList(start, end)
        }
    }
}