package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.FakeAgentRepository
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.FakeScheduleRepository
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.RouteUnavailableException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** The library with no agent focused: every agent's schedules, read a few agents at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleLibraryFleetTest {
    @Test
    fun withNoAgentFocusedEveryAgentsSchedulesListInRosterOrder() = runTest {
        val schedules = FakeScheduleRepository()
        val controller = controller(scheduleRepository = schedules)
        controller.selectFleet()
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state.fleet)
        assertNull(state.selectedAgentId)
        assertEquals(listOf("s1", "s2"), state.schedules.map { it.id })
        assertEquals(listOf("a1", "a2"), schedules.refreshedAgents.sorted())
        assertNull(state.errorMessage)
    }

    @Test
    fun oneAgentsFailureLeavesTheOtherAgentsListedInTheFleet() = runTest {
        val schedules = FakeScheduleRepository().apply { failingAgents += "a2" }
        val controller = controller(scheduleRepository = schedules)
        controller.selectFleet()
        advanceUntilIdle()
        assertEquals(listOf("s1"), controller.state.value.schedules.map { it.id })
        assertEquals("Could not load schedules for 1 agent.", controller.state.value.errorMessage)
        assertFalse(controller.state.value.isLoading)
    }

    @Test
    fun everyAgentFailingIsTheFleetsOnlyTotalFailure() = runTest {
        val schedules = FakeScheduleRepository().apply { failingAgents += listOf("a1", "a2") }
        val controller = controller(scheduleRepository = schedules)
        controller.selectFleet()
        advanceUntilIdle()
        assertTrue(controller.state.value.schedules.isEmpty())
        assertEquals("a1 down", controller.state.value.errorMessage)
    }

    @Test
    fun anAgentFilterNarrowsTheFleetToThatAgentAndAllWidensIt() = runTest {
        val clock = ScheduleClock(Instant.parse("2026-11-16T12:00:00Z"), TimeZone.UTC)
        val controller = controller()
        controller.selectFleet()
        advanceUntilIdle()
        controller.filterAgent(AgentId("a2"))
        val narrowed = controller.state.value
        assertEquals("a2", narrowed.scopeAgentId)
        assertEquals(listOf("s2"), SchedulesPageModel.defs(narrowed.crons, narrowed.schedules, narrowed.scopeAgentId, clock).map { it.id })
        controller.filterAgent(null)
        val all = controller.state.value
        assertNull(all.scopeAgentId)
        assertEquals(listOf("s1", "s2"), SchedulesPageModel.defs(all.crons, all.schedules, all.scopeAgentId, clock).map { it.id })
    }

    @Test
    fun anAgentFilterOnlyAppliesToTheFleet() = runTest {
        val controller = controller()
        controller.selectAgent("a1")
        advanceUntilIdle()
        controller.filterAgent(AgentId("a2"))
        assertNull(controller.state.value.agentFilter)
        assertEquals("a1", controller.state.value.scopeAgentId)
    }

    @Test
    fun focusMovingFromNoneToAnAgentAndBackSwapsFleetAndAgentState() = runTest {
        val controller = controller()
        controller.selectFleet()
        advanceUntilIdle()
        controller.filterAgent(AgentId("a1"))

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
        assertEquals(listOf("s1", "s2"), controller.state.value.schedules.map { it.id })
    }

    @Test
    fun aCronBackedFleetMergesEachAgentsCronsInRosterOrder() = runTest {
        val crons = CronScheduleSource { agent -> listOfNotNull(agent?.let { CronTask(id = "c-${it.value}", agentId = it.value) }) }
        val schedules = FakeScheduleRepository().apply { error = RouteUnavailableException() }
        val controller = controller(scheduleRepository = schedules, cronSource = crons)
        controller.selectFleet()
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state.cronMode)
        assertTrue(state.scheduleAdminAvailable)
        assertEquals(listOf("c-a1", "c-a2"), state.crons.map { it.id })
    }

    @Test
    fun fleetMutationsGoThroughTheRowsOwnAgent() = runTest {
        val schedules = FakeScheduleRepository()
        val controller = controller(scheduleRepository = schedules)
        controller.selectFleet()
        advanceUntilIdle()

        controller.deleteSchedule("s2", AgentId("a2"))
        advanceUntilIdle()
        assertEquals(listOf("s2"), schedules.deletedScheduleIds)
        assertEquals(listOf("s1"), controller.state.value.schedules.map { it.id })

        controller.createRecurringSchedule(ScheduleCreateRequest("a2", "Digest", "work", "0 18 * * *", "UTC"))
        advanceUntilIdle()
        assertEquals(1, schedules.createdSchedules.size)
        assertEquals(listOf("s1", "created-1"), controller.state.value.schedules.map { it.id })
        assertEquals("a2", controller.state.value.schedules.last().agentId)
        assertTrue(controller.state.value.fleet)

        controller.createRecurringSchedule(ScheduleCreateRequest(null, "Digest", "work", "0 18 * * *", "UTC"))
        advanceUntilIdle()
        assertEquals(1, schedules.createdSchedules.size, "a fleet create needs the agent the form chose")
    }

    @Test
    fun replacingOneAgentsCronsKeepsRosterOrder() {
        val roster = listOf("a1", "a2", "a3").map { AgentSummary(AgentId(it), it) }
        val crons = listOf("a1", "a2", "a3").map { CronTask(id = "c-$it", agentId = it) }
        val replaced = crons.replacingAgentCrons(AgentId("a2"), listOf(CronTask(id = "c-a2b", agentId = "a2")), roster)
        assertEquals(listOf("c-a1", "c-a2b", "c-a3"), replaced.map { it.id })
    }

    private fun TestScope.controller(
        scheduleRepository: FakeScheduleRepository = FakeScheduleRepository(),
        cronSource: CronScheduleSource? = null,
    ) = ScheduleLibraryController(
        agentRepository = FakeAgentRepository(),
        scheduleRepository = scheduleRepository,
        scope = this,
        scheduleAdminUnavailableMatcher = { it is RouteUnavailableException },
        cronSource = cronSource,
    )
}
