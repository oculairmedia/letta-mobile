package com.letta.mobile.ui.screens.groups

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.repository.GroupRepository
import com.letta.mobile.testutil.FakeGroupApi
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
class GroupAdminViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeGroupApi
    private lateinit var repository: GroupRepository
    private lateinit var viewModel: GroupAdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeGroupApi()
        fakeApi.groups.addAll(
            listOf(
                Group(id = GroupId("group-1"), managerType = "round_robin", agentIds = listOf(AgentId("agent-1")), description = "Primary group", projectId = ProjectId("proj-1")),
                Group(id = GroupId("group-2"), managerType = "supervisor", agentIds = listOf(AgentId("agent-2")), description = "Secondary group"),
            )
        )
        repository = GroupRepository(fakeApi)
        viewModel = GroupAdminViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadGroups populates state`() = runTest {
        viewModel.loadGroups()

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(2, state.data.groups.size)
    }

    @Test
    fun `updateSearchQuery filters groups locally`() = runTest {
        viewModel.loadGroups()
        viewModel.updateSearchQuery("supervisor")

        val filtered = viewModel.getFilteredGroups()
        assertEquals(1, filtered.size)
        assertEquals(GroupId("group-2"), filtered.first().id)
    }

    @Test
    fun `inspectGroup loads messages`() = runTest {
        viewModel.inspectGroup(GroupId("group-1"))

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(GroupId("group-1"), state.data.selectedGroup?.id)
        assertEquals(1, state.data.selectedMessages.size)
    }

    @Test
    fun `sendMessage delegates to repository and updates notice`() = runTest {
        viewModel.sendMessage(GroupId("group-1"), "hello")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertTrue(fakeApi.calls.contains("sendGroupMessage:group-1"))
        assertEquals(1, state.data.selectedMessages.size)
    }

    @Test
    fun `deleteGroup removes group`() = runTest {
        viewModel.deleteGroup(GroupId("group-1"))

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(1, state.data.groups.size)
    }
}
