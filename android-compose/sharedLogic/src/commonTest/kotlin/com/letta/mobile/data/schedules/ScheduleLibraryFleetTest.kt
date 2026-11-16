package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.InMemoryAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleLibraryFleetTest {
    private val clock = ScheduleClock(Instant.parse("2026-11-16T12:00:00Z"), TimeZone.UTC)

    @Test
    fun withNoAgentFocusedEveryAgentsSchedulesListInRosterOrder() = runTest {
        val schedules = FleetSchedules()
        val controller = controller(schedules)
        controller.selectFleet()
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state.fleet)
        assertNull(state.selectedAgentId)
        assertEquals(listOf("s1", "s2", "s3"), state.schedules.map { it.id })
        assertEquals(listOf("a1", "a2", "a3"), schedules.refreshed.sorted())
        assertNull(state.errorMessage)
    }

    @Test
    fun oneAgentsFailureLeavesTheOtherAgentsListed() = runTest {
        val schedules = FleetSchedules().apply { failing += "a2" }
        val controller = controller(schedules)
        controller.selectFleet()
        advanceUntilIdle()
        val state = controller.state.value
        assertEquals(listOf("s1", "s3"), state.schedules.map { it.id })
        assertEquals("Could not load schedules for 1 agent.", state.errorMessage)
        assertFalse(state.isLoading)
    }

    @Test
    fun everyAgentFailingIsTheOnlyTotalFailure() = runTest {
        val schedules = FleetSchedules().apply { failing += listOf("a1", "a2", "a3") }
        val controller = controller(schedules)
        controller.selectFleet()
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state.schedules.isEmpty())
        assertEquals("a1 down", state.errorMessage)
    }

    @Test
    fun anAgentFilterNarrowsTheFleetToThatAgentAndAllWidensIt() = runTest {
        val controller = controller(FleetSchedules())
        controller.selectFleet()
        advanceUntilIdle()
        controller.filterAgent("a2")
        var state = controller.state.value
        assertEquals("a2", state.scopeAgentId)
        assertEquals(listOf("s2"), SchedulesPageModel.defs(state.crons, state.schedules, state.scopeAgentId, clock).map { it.id })
        controller.filterAgent(null)
        state = controller.state.value
        assertNull(state.scopeAgentId)
        val all = SchedulesPageModel.defs(state.crons, state.schedules, state.scopeAgentId, clock)
        assertEquals(listOf("s1", "s2", "s3"), all.map { it.id })
    }

    @Test
    fun aFilterOnlyAppliesToTheFleet() = runTest {
        val controller = controller(FleetSchedules())
        controller.selectAgent("a1")
        advanceUntilIdle()
        controller.filterAgent("a2")
        assertNull(controller.state.value.agentFilter)
        assertEquals("a1", controller.state.value.scopeAgentId)
    }

    @Test
    fun focusMovingFromNoneToAnAgentAndBackSwapsFleetAndAgentState() = runTest {
        val controller = controller(FleetSchedules())
        controller.selectFleet()
        advanceUntilIdle()
        controller.filterAgent("a3")

        controller.selectAgent("a2")
        assertFalse(controller.state.value.fleet)
        assertNull(controller.state.value.agentFilter)
        assertTrue(controller.state.value.schedules.isEmpty(), "the fleet's rows must not linger under an agent")
        advanceUntilIdle()
        assertEquals(listOf("s2"), controller.state.value.schedules.map { it.id })

        controller.selectFleet()
        assertTrue(controller.state.value.fleet)
        assertNull(controller.state.value.selectedAgentId)
        assertTrue(controller.state.value.schedules.isEmpty(), "the agent's rows must not linger in the fleet")
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2", "s3"), controller.state.value.schedules.map { it.id })
    }

    @Test
    fun refreshingTheFleetReadsEveryAgentAgain() = runTest {
        val schedules = FleetSchedules()
        val controller = controller(schedules)
        controller.selectFleet()
        advanceUntilIdle()
        schedules.byAgent.getValue("a3") += schedule("s4", "a3")
        controller.loadData()
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2", "s3", "s4"), controller.state.value.schedules.map { it.id })
    }

    @Test
    fun aCronBackedFleetMergesEachAgentsCronsInRosterOrder() = runTest {
        val crons = FleetCrons()
        val schedules = FleetSchedules().apply { unavailable = true }
        val controller = controller(schedules, crons)
        controller.selectFleet()
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state.cronMode)
        assertEquals(listOf("c1", "c2", "c3"), state.crons.map { it.id })
        assertTrue(state.scheduleAdminAvailable)
    }

    @Test
    fun fleetMutationsGoThroughTheRowsOwnAgent() = runTest {
        val schedules = FleetSchedules()
        val controller = controller(schedules)
        controller.selectFleet()
        advanceUntilIdle()

        controller.deleteAgentSchedule("a2", "s2")
        advanceUntilIdle()
        assertEquals(listOf("a2" to "s2"), schedules.deleted)
        assertEquals(listOf("s1", "s3"), controller.state.value.schedules.map { it.id })

        controller.createRecurringSchedule(ScheduleCreateRequest("a3", "Digest", "work", "0 18 * * *", "UTC"))
        advanceUntilIdle()
        assertEquals(listOf("a3"), schedules.createdFor)
        assertEquals(listOf("s1", "s3", "created-a3"), controller.state.value.schedules.map { it.id })
        assertTrue(controller.state.value.fleet)
    }

    @Test
    fun aFleetCreateWithoutAnAgentDoesNothing() = runTest {
        val schedules = FleetSchedules()
        val controller = controller(schedules)
        controller.selectFleet()
        advanceUntilIdle()
        controller.createRecurringSchedule(ScheduleCreateRequest(null, "Digest", "work", "0 18 * * *", "UTC"))
        advanceUntilIdle()
        assertTrue(schedules.createdFor.isEmpty())
    }

    @Test
    fun replacingOneAgentsCronsKeepsRosterOrder() {
        val roster = listOf("a1", "a2", "a3").map { AgentSummary(AgentId(it), it) }
        val crons = listOf(cron("c1", "a1"), cron("c2", "a2"), cron("c3", "a3"))
        val replaced = crons.replacingAgentCrons(AgentId("a2"), listOf(cron("c2b", "a2")), roster)
        assertEquals(listOf("c1", "c2b", "c3"), replaced.map { it.id })
    }

    private fun TestScope.controller(schedules: FleetSchedules, crons: CronScheduleSource? = null) =
        ScheduleLibraryController(
            agentRepository = FleetAgents(),
            scheduleRepository = schedules,
            scope = this,
            scheduleAdminUnavailableMatcher = { it is NotServed },
            cronSource = crons,
        )

    private class NotServed : RuntimeException("not served")

    private class FleetCrons : CronScheduleSource {
        override suspend fun listCrons(agentId: AgentId?): List<CronTask> =
            agentId?.let { listOf(cron("c${it.value.removePrefix("a")}", it.value)) }.orEmpty()
    }

    private class FleetAgents : InMemoryAgentRepository() {
        override val agents: StateFlow<List<Agent>> = MutableStateFlow(
            listOf("a1", "a2", "a3").map { Agent(id = AgentId(it), name = "Agent $it") },
        )
        override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
        override val refreshError: StateFlow<Throwable?> = MutableStateFlow(null)
        override suspend fun countAgents(): Int = agents.value.size
        override suspend fun refreshAgents() = Unit
        override suspend fun listAgentSummaries(): List<AgentSummary> = agents.value.map { AgentSummary(it.id, it.name) }
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
        override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = error("unused")
        override suspend fun attachArchive(agentId: AgentId, archiveId: String) = Unit
        override suspend fun detachArchive(agentId: AgentId, archiveId: String) = Unit
    }

    private class FleetSchedules : IScheduleRepository {
        val byAgent = mutableMapOf(
            "a1" to mutableListOf(schedule("s1", "a1")),
            "a2" to mutableListOf(schedule("s2", "a2")),
            "a3" to mutableListOf(schedule("s3", "a3")),
        )
        val failing = mutableSetOf<String>()
        var unavailable = false
        val refreshed = mutableListOf<String>()
        val deleted = mutableListOf<Pair<String, String>>()
        val createdFor = mutableListOf<String>()

        override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> = flowOf(byAgent[agentId].orEmpty().toList())

        override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
            refreshed += agentId
            if (unavailable) throw NotServed()
            if (agentId in failing) error("$agentId down")
        }

        override suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage =
            byAgent.getValue(agentId).first { it.id == scheduledMessageId }

        override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
            createdFor += agentId
            return schedule("created-$agentId", agentId).also { byAgent.getOrPut(agentId) { mutableListOf() } += it }
        }

        override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
            deleted += agentId to scheduledMessageId
            byAgent.getValue(agentId).removeAll { it.id == scheduledMessageId }
        }
    }
}

private fun schedule(id: String, agentId: String) = ScheduledMessage(
    id = id,
    agentId = agentId,
    message = SchedulePayload(messages = listOf(ScheduleMessage(content = "hello", role = "user"))),
    schedule = ScheduleDefinition(type = "recurring", cronExpression = "0 9 * * *"),
)

private fun cron(id: String, agentId: String) = CronTask(id = id, agentId = agentId, cron = "0 9 * * *", recurring = true)
