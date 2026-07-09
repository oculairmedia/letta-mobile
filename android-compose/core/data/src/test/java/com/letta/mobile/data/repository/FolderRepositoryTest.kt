package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.testutil.FakeFolderApi
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
class FolderRepositoryTest {
    private lateinit var fakeApi: FakeFolderApi
    private lateinit var repository: FolderRepository

    @Before
    fun setup() {
        fakeApi = FakeFolderApi()
        repository = FolderRepository(fakeApi)
    }

    @Test
    fun `refreshFolders updates state flow`() = runTest {
        fakeApi.folders.add(Folder(id = FolderId("source-1"), name = "Knowledge"))

        repository.refreshFolders()

        assertEquals(1, repository.folders.first().size)
    }

    @Test
    fun `createFolder upserts cache`() = runTest {
        val created = repository.createFolder(FolderCreateParams(name = "Knowledge"))

        assertEquals("Knowledge", created.name)
        assertEquals(1, repository.folders.first().size)
    }

    @Test
    fun `updateFolder updates cache`() = runTest {
        fakeApi.folders.add(Folder(id = FolderId("source-1"), name = "Knowledge"))
        repository.refreshFolders()

        repository.updateFolder(FolderId("source-1"), FolderUpdateParams(description = "Docs"))

        assertEquals("Docs", repository.folders.first().first().description)
    }

    @Test
    fun `listFolderFiles delegates to api`() = runTest {
        val files = repository.listFolderFiles(FolderId("source-1"))

        assertEquals(1, files.size)
        assertTrue(fakeApi.calls.contains("listFolderFiles:source-1"))
    }
}
