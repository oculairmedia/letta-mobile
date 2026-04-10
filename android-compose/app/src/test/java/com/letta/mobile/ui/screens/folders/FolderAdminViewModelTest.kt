package com.letta.mobile.ui.screens.folders

import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.testutil.FakeFolderApi
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
class FolderAdminViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeFolderApi
    private lateinit var repository: FolderRepository
    private lateinit var viewModel: FolderAdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeFolderApi()
        fakeApi.folders.addAll(
            listOf(
                Folder(id = "folder-1", name = "Docs", description = "Primary docs", instructions = "Summarize"),
                Folder(id = "folder-2", name = "Logs", description = "Run logs", instructions = "Index"),
            )
        )
        repository = FolderRepository(fakeApi)
        viewModel = FolderAdminViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadFolders populates state`() = runTest {
        viewModel.loadFolders()

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(2, state.data.folders.size)
        assertEquals(2, state.data.folderMetadata?.totalSources)
    }

    @Test
    fun `updateSearchQuery filters folders locally`() = runTest {
        viewModel.loadFolders()
        viewModel.updateSearchQuery("logs")

        val filtered = viewModel.getFilteredFolders()
        assertEquals(1, filtered.size)
        assertEquals("folder-2", filtered.first().id)
    }

    @Test
    fun `inspectFolder loads related data`() = runTest {
        viewModel.inspectFolder("folder-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals("folder-1", state.data.selectedFolder?.id)
        assertEquals(1, state.data.selectedFolderAgents.size)
        assertEquals(1, state.data.selectedFolderFiles.size)
        assertEquals(1, state.data.selectedFolderPassages.size)
    }

    @Test
    fun `createFolder appends folder`() = runTest {
        viewModel.createFolder("New Folder", "Desc", "Instructions")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(3, state.data.folders.size)
    }

    @Test
    fun `deleteFolder removes folder`() = runTest {
        viewModel.deleteFolder("folder-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(1, state.data.folders.size)
    }
}
