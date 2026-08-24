package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import com.letta.mobile.data.model.EmbeddingConfig
import com.letta.mobile.testutil.FakeArchiveApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ArchiveRepositoryTest {
    private lateinit var fakeApi: FakeArchiveApi
    private lateinit var repository: ArchiveRepository

    @Before
    fun setup() {
        fakeApi = FakeArchiveApi()
        repository = ArchiveRepository(fakeApi)
    }

    @Test
    fun `refreshArchives updates state flow`() = runTest {
        fakeApi.archives.add(Archive(id = "archive-1", name = "Docs"))

        repository.refreshArchives()

        assertEquals(1, repository.archives.first().size)
    }

    @Test
    fun `createArchive upserts cache`() = runTest {
        val created = repository.createArchive(
            ArchiveCreateParams(
                name = "Docs",
                embeddingConfig = EmbeddingConfig(embeddingModel = "text-embedding-3-small"),
            )
        )

        assertEquals("Docs", created.name)
        assertEquals(1, repository.archives.first().size)
    }

    @Test
    fun `updateArchive updates cache`() = runTest {
        fakeApi.archives.add(Archive(id = "archive-1", name = "Docs"))
        repository.refreshArchives()

        val updated = repository.updateArchive("archive-1", ArchiveUpdateParams(description = "Shared docs"))

        assertEquals("Shared docs", updated.description)
        assertEquals("Shared docs", repository.archives.first().first().description)
    }

    @Test
    fun `listAgentsForArchive delegates to api`() = runTest {
        val agents = repository.listAgentsForArchive("archive-1")

        assertEquals(1, agents.size)
        assertTrue(fakeApi.calls.contains("listAgentsForArchive:archive-1"))
    }
}
