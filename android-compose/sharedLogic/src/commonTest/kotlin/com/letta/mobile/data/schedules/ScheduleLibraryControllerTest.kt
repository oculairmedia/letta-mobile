package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleLibraryControllerTest {
    @Test
    fun loadDataSelectsFirstAgentAndLoadsSchedules() = runTest {
        val controller = controller()

        controller.start()
        advanceUntilIdle()

        assertEquals("a1", controller.state.value.selectedAgentId)
        assertEquals(listOf("s1"), controller.state.value.schedules.map { it.id })
    }

    @Test
    fun loadDataPopulatesDropdownFromSlimAgentSummariesNotFullRefresh() = runTest {
        val agentRepository = FakeAgentRepository()
        val controller = controller(agentRepository = agentRepository)

        controller.start()
        advanceUntilIdle()

        // Dropdown populated from the slim summaries path...
        assertEquals(listOf("a1", "a2"), controller.state.value.agents.map { it.id.value })
        assertEquals(listOf("Agent One", "Agent Two"), controller.state.value.agents.map { it.name })
        assertTrue(agentRepository.calls.contains("listAgentSummaries"))
        // ...and NOT the full ~621KB refreshAgents() payload.
        assertTrue(agentRepository.calls.none { it == "refreshAgents" })
    }

    @Test
    fun `loadData sets empty schedules successfully without error`() = runTest {
        val scheduleRepository = FakeScheduleRepository()
        scheduleRepository.clearSchedules()
        val controller = controller(scheduleRepository = scheduleRepository)

        controller.start()
        advanceUntilIdle()

        assertEquals("a1", controller.state.value.selectedAgentId)
        assertEquals(emptyList(), controller.state.value.schedules)
        assertEquals(true, controller.state.value.scheduleAdminAvailable)
        assertEquals("No schedules for this agent.", controller.state.value.emptyMessage)
    }

    @Test
    fun selectAgentLoadsThatAgentsSchedules() = runTest {
        val controller = controller()
        controller.start()
        advanceUntilIdle()

        controller.selectAgent("a2")
        advanceUntilIdle()

        assertEquals("a2", controller.state.value.selectedAgentId)
        assertEquals(listOf("s2"), controller.state.value.schedules.map { it.id })
    }

    @Test
    fun createScheduleDelegatesAndRefreshesSelectedAgentSchedules() = runTest {
        val scheduleRepository = FakeScheduleRepository()
        val controller = controller(scheduleRepository = scheduleRepository)
        controller.start()
        advanceUntilIdle()

        controller.createSchedule(
            "a2",
            ScheduleCreateParams(
                messages = listOf(ScheduleMessage(content = "hello", role = "user")),
                schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, scheduleRepository.createdSchedules.size)
        assertEquals("a2", controller.state.value.selectedAgentId)
        assertTrue(controller.state.value.schedules.any { it.id == "created-1" })
    }

    @Test
    fun deleteScheduleDelegatesAndRefreshesSchedules() = runTest {
        val scheduleRepository = FakeScheduleRepository()
        val controller = controller(scheduleRepository = scheduleRepository)
        controller.start()
        advanceUntilIdle()

        controller.deleteSchedule("s1")
        advanceUntilIdle()

        assertEquals(listOf("s1"), scheduleRepository.deletedScheduleIds)
        assertEquals(emptyList(), controller.state.value.schedules.map { it.id })
    }

    @Test
    fun unavailableScheduleRoutesPublishCommonUnavailableState() = runTest {
        val scheduleRepository = FakeScheduleRepository().apply {
            error = RouteUnavailableException()
        }
        val controller = controller(scheduleRepository = scheduleRepository)

        controller.start()
        advanceUntilIdle()

        assertEquals(false, controller.state.value.scheduleAdminAvailable)
        assertEquals(SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE, controller.state.value.scheduleAdminMessage)
        assertEquals(emptyList(), controller.state.value.schedules)
    }

    @Test
    fun nonRouteParseAndTransportErrorsAreNotMislabeledAsAdminUnavailable() = runTest {
        val scheduleRepository = FakeScheduleRepository().apply {
            error = RuntimeException("parse error")
        }
        val controller = controller(scheduleRepository = scheduleRepository)

        controller.start()
        advanceUntilIdle()

        assertEquals(true, controller.state.value.scheduleAdminAvailable)
        assertEquals("parse error", controller.state.value.errorMessage)
    }

    @Test
    fun nativeRouteUnavailableFallsBackToCronListNotUnavailableState() = runTest {
        val scheduleRepository = FakeScheduleRepository().apply {
            error = RouteUnavailableException()
        }
        val crons = listOf(
            CronTask(id = "c1", agentId = "a1", name = "Daily digest", cron = "0 9 * * *", recurring = true),
            CronTask(id = "c2", agentId = "a1", name = "Hourly", cron = "0 * * * *", recurring = true),
        )
        val controller = controller(
            scheduleRepository = scheduleRepository,
            cronSource = CronScheduleSource { crons },
        )

        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(true, state.cronMode)
        assertEquals(true, state.scheduleAdminAvailable)
        assertEquals(listOf("c1", "c2"), state.crons.map { it.id })
        assertEquals("No schedules for this agent.", state.emptyMessage)
    }

    @Test
    fun nativeRouteUnavailableWithEmptyCronListShowsEmptyNotUnavailable() = runTest {
        val scheduleRepository = FakeScheduleRepository().apply {
            error = RouteUnavailableException()
        }
        val controller = controller(
            scheduleRepository = scheduleRepository,
            cronSource = CronScheduleSource { emptyList() },
        )

        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(true, state.cronMode)
        assertEquals(true, state.scheduleAdminAvailable)
        assertEquals(emptyList(), state.crons)
        assertEquals("No schedules for this agent.", state.emptyMessage)
    }

    @Test
    fun bothNativeAndCronRoutesUnavailableShowsUnavailableState() = runTest {
        val scheduleRepository = FakeScheduleRepository().apply {
            error = RouteUnavailableException()
        }
        val controller = controller(
            scheduleRepository = scheduleRepository,
            cronSource = CronScheduleSource { throw RouteUnavailableException() },
        )

        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(false, state.cronMode)
        assertEquals(false, state.scheduleAdminAvailable)
        assertEquals(SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE, state.scheduleAdminMessage)
        assertEquals(emptyList(), state.crons)
    }

    @Test
    fun nativeRouteUnavailableWithoutCronSourceStillShowsUnavailable() = runTest {
        val scheduleRepository = FakeScheduleRepository().apply {
            error = RouteUnavailableException()
        }
        val controller = controller(scheduleRepository = scheduleRepository)

        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(false, state.cronMode)
        assertEquals(false, state.scheduleAdminAvailable)
        assertEquals(SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE, state.scheduleAdminMessage)
    }

    @Test
    fun selectAgentOnUnavailableRouteFallsBackToCronList() = runTest {
        val scheduleRepository = FakeScheduleRepository()
        val crons = listOf(CronTask(id = "c1", agentId = "a2", name = "A2 cron", cron = "0 9 * * *", recurring = true))
        val controller = controller(
            scheduleRepository = scheduleRepository,
            cronSource = CronScheduleSource { crons },
        )
        controller.start()
        advanceUntilIdle()

        // Now make the native route unavailable and switch agents.
        scheduleRepository.error = RouteUnavailableException()
        controller.selectAgent("a2")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals("a2", state.selectedAgentId)
        assertEquals(true, state.cronMode)
        assertEquals(true, state.scheduleAdminAvailable)
        assertEquals(listOf("c1"), state.crons.map { it.id })
    }

    @Test
    fun malformedAndEmptySchedulesConvertGracefullyWithoutCrashing() {
        val emptyMessageSchedule = ScheduledMessage(
            id = "empty",
            agentId = "a1",
            message = SchedulePayload(messages = emptyList()),
            schedule = ScheduleDefinition(type = "recurring", cronExpression = null),
            nextScheduledTime = null
        )

        val item1 = emptyMessageSchedule.toScheduleLibraryItem()
        assertEquals("", item1.messagePreview)
        assertTrue(item1.timing is ScheduleTiming.Recurring)
        assertEquals("", (item1.timing as ScheduleTiming.Recurring).cronExpression)

        val malformedOneTime = emptyMessageSchedule.copy(
            schedule = ScheduleDefinition(type = "one-time", scheduledAt = null)
        )

        val item2 = malformedOneTime.toScheduleLibraryItem()
        assertEquals("", item2.messagePreview)
        assertTrue(item2.timing is ScheduleTiming.OneTime)
        assertEquals("", (item2.timing as ScheduleTiming.OneTime).displayTime)
    }

    @Test
    fun `initialAgentId pre-selects that agent instead of the first`() = runTest {
        val controller = controller(initialAgentId = "a2")

        controller.start()
        advanceUntilIdle()

        assertEquals("a2", controller.state.value.selectedAgentId)
        assertEquals(listOf("s2"), controller.state.value.schedules.map { it.id })
    }

    @Test
    fun `null initialAgentId falls back to existing first-agent behaviour`() = runTest {
        val controller = controller(initialAgentId = null)

        controller.start()
        advanceUntilIdle()

        assertEquals("a1", controller.state.value.selectedAgentId)
        assertEquals(listOf("s1"), controller.state.value.schedules.map { it.id })
    }

    @Test
    fun `initialAgentId not in agent list falls back gracefully to first agent`() = runTest {
        val controller = controller(initialAgentId = "does-not-exist")

        controller.start()
        advanceUntilIdle()

        assertEquals("a1", controller.state.value.selectedAgentId)
        assertEquals(listOf("s1"), controller.state.value.schedules.map { it.id })
    }

    private fun TestScope.controller(
        agentRepository: FakeAgentRepository = FakeAgentRepository(),
        scheduleRepository: FakeScheduleRepository = FakeScheduleRepository(),
        cronSource: CronScheduleSource? = null,
        initialAgentId: String? = null,
    ) = ScheduleLibraryController(
        agentRepository = agentRepository,
        scheduleRepository = scheduleRepository,
        scope = this,
        scheduleAdminUnavailableMatcher = { it is RouteUnavailableException },
        cronSource = cronSource,
        initialAgentId = initialAgentId,
    )

    private class RouteUnavailableException : RuntimeException("route unavailable")

    private class FakeAgentRepository : IAgentRepository {
        private val agentsFlow = MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent One"),
                Agent(id = AgentId("a2"), name = "Agent Two"),
            ),
        )
        val calls = mutableListOf<String>()
        override val agents: StateFlow<List<Agent>> = agentsFlow.asStateFlow()
        override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
        override val refreshError: StateFlow<Throwable?> = MutableStateFlow(null)

        override suspend fun countAgents(): Int = agents.value.size
        override suspend fun refreshAgents() {
            calls += "refreshAgents"
        }

        override suspend fun listAgentSummaries(): List<AgentSummary> {
            calls += "listAgentSummaries"
            return agents.value.map { AgentSummary(id = it.id, name = it.name, description = it.description) }
        }
        override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = false
        override fun getCachedAgent(id: AgentId): Agent? = agents.value.firstOrNull { it.id == id }
        override fun getAgent(id: AgentId): Flow<Agent> = flowOf(checkNotNull(getCachedAgent(id)))
        override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview =
            error("unused")
        override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) = operation()
        override suspend fun createAgent(params: AgentCreateParams): Agent = error("unused")
        override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = error("unused")
        override suspend fun deleteAgent(id: AgentId) = Unit
        override suspend fun exportAgent(id: AgentId): String = error("unused")
        override suspend fun importAgent(
            fileName: String,
            fileBytes: ByteArray,
            overrideName: String?,
            overrideExistingTools: Boolean?,
            projectId: ProjectId?,
            stripMessages: Boolean?,
        ): ImportedAgentsResponse = error("unused")
        override suspend fun attachArchive(agentId: AgentId, archiveId: String) = Unit
        override suspend fun detachArchive(agentId: AgentId, archiveId: String) = Unit
    }

    private class FakeScheduleRepository : IScheduleRepository {
        private val schedulesByAgent = mutableMapOf(
            "a1" to mutableListOf(sampleSchedule("s1", "a1")),
            "a2" to mutableListOf(sampleSchedule("s2", "a2")),
        )
        var error: Throwable? = null
        val createdSchedules = mutableListOf<ScheduleCreateParams>()
        val deletedScheduleIds = mutableListOf<String>()

        fun clearSchedules() {
            schedulesByAgent.clear()
        }

        override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> =
            flowOf(schedulesByAgent[agentId].orEmpty())

        override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
            error?.let { throw it }
        }

        override suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage =
            schedulesByAgent[agentId].orEmpty().first { it.id == scheduledMessageId }

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
