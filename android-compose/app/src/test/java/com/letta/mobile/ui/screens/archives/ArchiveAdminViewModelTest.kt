package com.letta.mobile.ui.screens.archives

import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.testutil.FakeArchiveApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveAdminViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeArchiveApi
    private lateinit var repository: ArchiveRepository
    private lateinit var viewModel: ArchiveAdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeArchiveApi()
        fakeApi.archives.addAll(
            listOf(
                com.letta.mobile.data.model.Archive(id = "archive-1", name = "Primary Archive", description = "Knowledge"),
                com.letta.mobile.data.model.Archive(id = "archive-2", name = "Logs", description = "Events"),
            )
        )
        repository = ArchiveRepository(fakeApi)
        viewModel = ArchiveAdminViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadArchives populates state`() = runTest {
        viewModel.loadArchives()

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(2, state.data.archives.size)
    }

    @Test
    fun `updateSearchQuery filters archives locally`() = runTest {
        viewModel.loadArchives()
        viewModel.updateSearchQuery("logs")

        val filtered = viewModel.getFilteredArchives()
        assertEquals(1, filtered.size)
        assertEquals("archive-2", filtered.first().id)
    }

    @Test
    fun `inspectArchive loads archive agents`() = runTest {
        viewModel.inspectArchive("archive-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals("archive-1", state.data.selectedArchive?.id)
        assertEquals(1, state.data.selectedArchiveAgents.size)
    }

    @Test
    fun `createArchive appends archive`() = runTest {
        viewModel.createArchive("Fresh", "Desc", "text-embedding-3-small")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(3, state.data.archives.size)
    }

    @Test
    fun `deleteArchive removes archive`() = runTest {
        viewModel.deleteArchive("archive-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(1, state.data.archives.size)
    }
}
