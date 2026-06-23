package com.letta.mobile.ui.screens.schedules

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.data.schedules.CronTask
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ScheduleListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeAgentRepo: FakeAgentRepo
    private lateinit var fakeScheduleRepo: FakeScheduleRepo
    private lateinit var fakeScheduleApi: FakeScheduleApi
    private lateinit var viewModel: ScheduleListViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeAgentRepo = FakeAgentRepo()
        fakeScheduleRepo = FakeScheduleRepo()
        // Cron route unavailable by default: the "unavailable" tests below
        // exercise the case where BOTH the native schedule route and the
        // /v1/crons fallback are missing.
        fakeScheduleApi = FakeScheduleApi()
        viewModel = ScheduleListViewModel(SavedStateHandle(), fakeAgentRepo, fakeScheduleRepo, fakeScheduleApi)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadData selects first agent and loads schedules`() = runTest {
        viewModel.loadData()

        val state = awaitSuccessState()
        assertEquals("a1", state.selectedAgentId)
        assertEquals(1, state.schedules.size)
    }

    @Test
    fun `route agentId pre-selects that agent instead of the first`() = runTest {
        // Opened FROM A CHAT with agent a2: SchedulesRoute(agentId = "a2") is
        // delivered via SavedStateHandle and must pre-select a2, not the
        // default first agent (a1).
        val vm = ScheduleListViewModel(
            SavedStateHandle(mapOf("agentId" to "a2")),
            fakeAgentRepo,
            fakeScheduleRepo,
            fakeScheduleApi,
        )

        val state = vm.uiState.first { it is com.letta.mobile.ui.common.UiState.Success }
            .let { (it as com.letta.mobile.ui.common.UiState.Success).data }
        assertEquals("a2", state.selectedAgentId)
        assertEquals("s2", state.schedules.first().id)
    }

    @Test
    fun `loadData empty schedules state handles success and displays 0 items`() = runTest {
        fakeScheduleRepo.clearSchedules()
        viewModel.loadData()

        val state = awaitSuccessState()
        assertEquals("a1", state.selectedAgentId)
        assertEquals(0, state.schedules.size)
        assertEquals(true, state.scheduleAdminAvailable)
    }

    @Test
    fun `selectAgent loads that agents schedules`() = runTest {
        awaitSuccessState()
        viewModel.selectAgent("a2")

        val state = awaitSuccessState()
        assertEquals("a2", state.selectedAgentId)
        assertEquals("s2", state.schedules.first().id)
    }

    @Test
    fun `createSchedule delegates to repository`() = runTest {
        awaitSuccessState()
        viewModel.createSchedule(
            "a2",
            ScheduleCreateParams(
                messages = listOf(ScheduleMessage(content = "hello", role = "user")),
                schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
            )
        )

        assertEquals(1, fakeScheduleRepo.createdSchedules.size)
        val state = awaitSuccessState()
        assertEquals("a2", state.selectedAgentId)
        assertEquals(true, state.schedules.any { it.id == "created-1" })
    }

    @Test
    fun `deleteSchedule delegates to repository`() = runTest {
        awaitSuccessState()
        viewModel.deleteSchedule("s1")

        assertEquals(listOf("s1"), fakeScheduleRepo.deletedScheduleIds)
    }

    @Test
    fun `loadData shows unavailable state when schedule routes are missing`() = runTest {
        fakeScheduleRepo.error = ApiException(404, "Not found")

        viewModel.loadData()

        val state = awaitSuccessState()
        assertEquals(false, state.scheduleAdminAvailable)
        assertEquals("Schedule admin isn't available on this Letta server.", state.scheduleAdminMessage)
        assertEquals(0, state.schedules.size)
    }

    @Test
    fun `selectAgent shows unavailable state when schedule routes are missing`() = runTest {
        awaitSuccessState()
        fakeScheduleRepo.error = ApiException(404, "Not found")

        viewModel.selectAgent("a2")

        val state = awaitSuccessState()
        assertEquals("a2", state.selectedAgentId)
        assertEquals(false, state.scheduleAdminAvailable)
    }

    @Test
    fun `loadData falls back to cron list when native route missing and cron route available`() = runTest {
        fakeScheduleRepo.error = ApiException(404, "Not found")
        fakeScheduleApi.cronRouteAvailable = true
        fakeScheduleApi.crons = mutableListOf(
            CronTask(id = "c1", agentId = "a1", name = "Daily", cron = "0 9 * * *", recurring = true),
        )

        viewModel.loadData()

        val state = awaitSuccessState()
        assertEquals(true, state.cronMode)
        assertEquals(true, state.scheduleAdminAvailable)
        assertEquals(listOf("c1"), state.crons.map { it.id })
    }

    @Test
    fun `loadData shows error state for non-route parse and transport errors`() = runTest {
        fakeScheduleRepo.error = RuntimeException("Transport error")

        viewModel.loadData()

        val state = viewModel.uiState.first { it is com.letta.mobile.ui.common.UiState.Error }
            .let { (it as com.letta.mobile.ui.common.UiState.Error).message }
        assertEquals("Failed to load schedules", state)
    }

    @Test
    fun `loadData schedules with empty messages and missing schedule info convert gracefully`() = runTest {
        val emptyMessageSchedule = ScheduledMessage(
            id = "empty",
            agentId = "a1",
            message = SchedulePayload(messages = emptyList()),
            schedule = ScheduleDefinition(type = "recurring", cronExpression = null),
            nextScheduledTime = null
        )
        fakeScheduleRepo.setSchedules("a1", listOf(emptyMessageSchedule))

        viewModel.loadData()

        val state = awaitSuccessState()
        assertEquals(1, state.displayItems.size)
        val item = state.displayItems.first()
        assertEquals("", item.messagePreview)
        assert(item.timing is com.letta.mobile.data.schedules.ScheduleTiming.Recurring)
        assertEquals("", (item.timing as com.letta.mobile.data.schedules.ScheduleTiming.Recurring).cronExpression)
    }

    private suspend fun awaitSuccessState(): ScheduleListUiState {
        return viewModel.uiState.first { it is com.letta.mobile.ui.common.UiState.Success }
            .let { (it as com.letta.mobile.ui.common.UiState.Success).data }
    }

    private class FakeAgentRepo : AgentRepository(FakeAgentApi(), mockk(relaxed = true)) {
        private val _agents = MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent One"),
                Agent(id = AgentId("a2"), name = "Agent Two"),
            )
        )

        override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
        override suspend fun refreshAgents() {}
        override fun getAgent(id: String): Flow<Agent> = flow { emit(_agents.value.first { it.id.value == id }) }
        override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams): Agent = _agents.value.first()
        override suspend fun updateAgent(id: String, params: com.letta.mobile.data.model.AgentUpdateParams): Agent = _agents.value.first { it.id.value == id }
        override suspend fun deleteAgent(id: String) {}
    }

    private class FakeScheduleRepo : ScheduleRepository(FakeScheduleApi()) {
        private val schedulesByAgent = mutableMapOf(
            "a1" to mutableListOf(sampleSchedule("s1", "a1")),
            "a2" to mutableListOf(sampleSchedule("s2", "a2")),
        )
        var error: Exception? = null
        val createdSchedules = mutableListOf<ScheduleCreateParams>()
        val deletedScheduleIds = mutableListOf<String>()

        override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> = flow {
            emit(schedulesByAgent[agentId].orEmpty())
        }

        override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
            error?.let { throw it }
        }

        fun clearSchedules() {
            schedulesByAgent.clear()
        }

        fun setSchedules(agentId: String, schedules: List<ScheduledMessage>) {
            schedulesByAgent[agentId] = schedules.toMutableList()
        }

        override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
            error?.let { throw it }
            createdSchedules.add(params)
            val schedule = sampleSchedule("created-${createdSchedules.size}", agentId)
            schedulesByAgent.getOrPut(agentId) { mutableListOf() }.add(schedule)
            return schedule
        }

        override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
            error?.let { throw it }
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
