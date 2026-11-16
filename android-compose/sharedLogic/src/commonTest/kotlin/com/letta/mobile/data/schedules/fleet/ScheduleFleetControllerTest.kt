package com.letta.mobile.data.schedules.fleet

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.schedules.CronScheduleSource
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleCreateRequest
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.FakeAgentRepository
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.FakeScheduleRepository
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.RouteUnavailableException
import com.letta.mobile.data.schedules.SchedulesPageModel
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

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleFleetControllerTest {
    @Test
    fun everyAgentsSchedulesListInRosterOrder() = runTest {
        val schedules = FakeScheduleRepository()
        val controller = controller(schedules)
        controller.refresh()
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state.fleet)
        assertNull(state.selectedAgentId)
        assertEquals(listOf("s1", "s2"), state.schedules.map { it.id })
        assertEquals(listOf("a1", "a2"), schedules.refreshedAgents.sorted())
        assertNull(state.errorMessage)
        assertFalse(state.isLoading)
    }

    @Test
    fun oneAgentsFailureLeavesTheOtherAgentsListed() = runTest {
        val schedules = FakeScheduleRepository().apply { failingAgents += "a2" }
        val controller = controller(schedules)
        controller.refresh()
        advanceUntilIdle()
        assertEquals(listOf("s1"), controller.state.value.schedules.map { it.id })
        assertEquals("Could not load schedules for 1 agent.", controller.state.value.errorMessage)
    }

    @Test
    fun everyAgentFailingIsTheOnlyTotalFailure() = runTest {
        val schedules = FakeScheduleRepository().apply { failingAgents += listOf("a1", "a2") }
        val controller = controller(schedules)
        controller.refresh()
        advanceUntilIdle()
        assertTrue(controller.state.value.schedules.isEmpty())
        assertEquals("a1 down", controller.state.value.errorMessage)
    }

    @Test
    fun anAgentFilterNarrowsTheRowsToThatAgentAndAllWidensThem() = runTest {
        val clock = ScheduleClock(Instant.parse("2026-11-16T12:00:00Z"), TimeZone.UTC)
        val controller = controller(FakeScheduleRepository())
        controller.refresh()
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
    fun aFilterSurvivesARefreshWhileItsAgentIsListed() = runTest {
        val controller = controller(FakeScheduleRepository())
        controller.refresh()
        advanceUntilIdle()
        controller.filterAgent(AgentId("a1"))
        controller.refresh()
        advanceUntilIdle()
        assertEquals("a1", controller.state.value.agentFilter)
    }

    @Test
    fun aCronBackedHostMergesEachAgentsCronsInRosterOrder() = runTest {
        val crons = CronScheduleSource { agent -> listOfNotNull(agent?.let { CronTask(id = "c-${it.value}", agentId = it.value) }) }
        val schedules = FakeScheduleRepository().apply { error = RouteUnavailableException() }
        val controller = controller(schedules, crons)
        controller.refresh()
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state.cronMode)
        assertTrue(state.scheduleAdminAvailable)
        assertEquals(listOf("c-a1", "c-a2"), state.crons.map { it.id })

        val probes = schedules.refreshScheduleCallCount
        controller.refresh()
        advanceUntilIdle()
        assertEquals(probes, schedules.refreshScheduleCallCount, "the unserved native route is not asked again")
    }

    @Test
    fun aFailedCronReadKeepsTheAgentsNativeSchedules() = runTest {
        val crons = CronScheduleSource { _ -> error("crons down") }
        val controller = controller(FakeScheduleRepository(), crons)
        controller.refresh()
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2"), controller.state.value.schedules.map { it.id })
        assertNull(controller.state.value.errorMessage)
    }

    @Test
    fun mutationsGoThroughTheRowsOwnAgent() = runTest {
        val schedules = FakeScheduleRepository()
        val controller = controller(schedules)
        controller.refresh()
        advanceUntilIdle()

        controller.deleteNative(controller.state.value.schedules.single { it.id == "s2" })
        advanceUntilIdle()
        assertEquals(listOf("s2"), schedules.deletedScheduleIds)
        assertEquals(listOf("s1"), controller.state.value.schedules.map { it.id })

        controller.createSchedule(ScheduleCreateRequest("a2", "Digest", "work", "0 18 * * *", "UTC"))
        advanceUntilIdle()
        assertEquals(listOf("s1", "created-1"), controller.state.value.schedules.map { it.id })
        assertEquals("a2", controller.state.value.schedules.last().agentId)

        controller.createSchedule(ScheduleCreateRequest(null, "Digest", "work", "0 18 * * *", "UTC"))
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

    private fun TestScope.controller(schedules: FakeScheduleRepository, crons: CronScheduleSource? = null) =
        ScheduleFleetController(
            ScheduleFleetSources(FakeAgentRepository(), schedules, crons),
            ScheduleFleetPolicy(nativeUnavailable = { it is RouteUnavailableException }),
            this,
        )
}
