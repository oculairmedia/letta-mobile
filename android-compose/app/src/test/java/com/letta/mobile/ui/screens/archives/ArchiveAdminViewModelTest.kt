package com.letta.mobile.ui.screens.archives

import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeArchiveApi
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ArchiveAdminViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeArchiveApi
    private lateinit var fakeAgentApi: FakeAgentApi
    private lateinit var repository: ArchiveRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var viewModel: ArchiveAdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeArchiveApi()
        fakeAgentApi = FakeAgentApi()
        fakeApi.archives.addAll(
            listOf(
                com.letta.mobile.data.model.Archive(id = "archive-1", name = "Primary Archive", description = "Knowledge"),
                com.letta.mobile.data.model.Archive(id = "archive-2", name = "Logs", description = "Events"),
            )
        )
        fakeAgentApi.agents.addAll(
            listOf(
                com.letta.mobile.testutil.TestData.agent(id = "agent-1", name = "Attached Agent"),
                com.letta.mobile.testutil.TestData.agent(id = "agent-2", name = "Available Agent"),
            )
        )
        repository = ArchiveRepository(fakeApi)
        agentRepository = AgentRepository(fakeAgentApi, mockk(relaxed = true))
        viewModel = ArchiveAdminViewModel(repository, agentRepository)
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

    @Test
    fun `getAvailableAgentsForArchive excludes already attached agents`() = runTest {
        viewModel.inspectArchive("archive-1")

        val availableAgents = viewModel.getAvailableAgentsForArchive()

        assertEquals(1, availableAgents.size)
        assertEquals("agent-2", availableAgents.first().id.value)
    }

    @Test
    fun `attachArchiveToAgent delegates through agent repository`() = runTest {
        viewModel.inspectArchive("archive-1")
        var successCalled = false

        viewModel.attachArchiveToAgent("archive-1", "agent-2") { successCalled = true }

        assertTrue(successCalled)
        assertTrue(fakeAgentApi.calls.contains("attachArchive:agent-2:archive-1"))
        assertTrue(fakeApi.calls.count { it == "listAgentsForArchive:archive-1" } >= 2)
    }

    @Test
    fun `detachArchiveFromAgent delegates through agent repository`() = runTest {
        viewModel.inspectArchive("archive-1")

        viewModel.detachArchiveFromAgent("archive-1", "agent-1")

        assertTrue(fakeAgentApi.calls.contains("detachArchive:agent-1:archive-1"))
        assertTrue(fakeApi.calls.count { it == "listAgentsForArchive:archive-1" } >= 2)
    }
}
