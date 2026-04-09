package com.letta.mobile.ui.screens.schedules

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeScheduleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeAgentRepo: FakeAgentRepo
    private lateinit var fakeScheduleRepo: FakeScheduleRepo
    private lateinit var viewModel: ScheduleListViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeAgentRepo = FakeAgentRepo()
        fakeScheduleRepo = FakeScheduleRepo()
        viewModel = ScheduleListViewModel(fakeAgentRepo, fakeScheduleRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadData selects first agent and loads schedules`() = runTest {
        viewModel.loadData()

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals("a1", state.data.selectedAgentId)
        assertEquals(1, state.data.schedules.size)
    }

    @Test
    fun `selectAgent loads that agents schedules`() = runTest {
        viewModel.selectAgent("a2")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals("a2", state.data.selectedAgentId)
        assertEquals("s2", state.data.schedules.first().id)
    }

    @Test
    fun `createSchedule delegates to repository`() = runTest {
        viewModel.createSchedule(
            "a2",
            ScheduleCreateParams(
                messages = listOf(ScheduleMessage(content = "hello", role = "user")),
                schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
            )
        )

        assertEquals(1, fakeScheduleRepo.createdSchedules.size)
        assertEquals("created-1", (viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success).data.schedules.first().id)
    }

    @Test
    fun `deleteSchedule delegates to repository`() = runTest {
        viewModel.deleteSchedule("s1")

        assertEquals(listOf("s1"), fakeScheduleRepo.deletedScheduleIds)
    }

    private class FakeAgentRepo : AgentRepository(FakeAgentApi()) {
        private val _agents = MutableStateFlow(
            listOf(
                Agent(id = "a1", name = "Agent One"),
                Agent(id = "a2", name = "Agent Two"),
            )
        )

        override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
        override suspend fun refreshAgents() {}
        override fun getAgent(id: String): Flow<Agent> = flow { emit(_agents.value.first { it.id == id }) }
        override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams): Agent = _agents.value.first()
        override suspend fun updateAgent(id: String, params: com.letta.mobile.data.model.AgentUpdateParams): Agent = _agents.value.first { it.id == id }
        override suspend fun deleteAgent(id: String) {}
    }

    private class FakeScheduleRepo : ScheduleRepository(FakeScheduleApi()) {
        private val schedulesByAgent = mutableMapOf(
            "a1" to mutableListOf(sampleSchedule("s1", "a1")),
            "a2" to mutableListOf(sampleSchedule("s2", "a2")),
        )
        val createdSchedules = mutableListOf<ScheduleCreateParams>()
        val deletedScheduleIds = mutableListOf<String>()

        override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> = flow {
            emit(schedulesByAgent[agentId].orEmpty())
        }

        override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {}

        override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
            createdSchedules.add(params)
            val schedule = sampleSchedule("created-${createdSchedules.size}", agentId)
            schedulesByAgent.getOrPut(agentId) { mutableListOf() }.add(schedule)
            return schedule
        }

        override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
            deletedScheduleIds.add(scheduledMessageId)
            schedulesByAgent[agentId]?.removeAll { it.id == scheduledMessageId }
        }
    }
}

private fun sampleSchedule(id: String, agentId: String) = ScheduledMessage(
    id = id,
    agentId = agentId,
    message = SchedulePayload(messages = listOf(ScheduleMessage(content = "hello", role = "user"))),
    schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
    nextScheduledTime = "2026-04-09T10:00:00Z",
)
