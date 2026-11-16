package com.letta.mobile.ui.shell.pages.schedules

import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.RunRecord
import com.letta.mobile.data.schedules.ScheduleLibraryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What the fleet view's agent filter keeps: rows, run history and the controls on them. */
class SchedulesFleetScopeTest {
    private val at = Instant.parse("2026-11-16T12:00:00Z")
    private val native = ScheduledMessage(
        id = "s1",
        agentId = "a1",
        message = SchedulePayload(messages = listOf(ScheduleMessage(content = "hi", role = "user"))),
        schedule = ScheduleDefinition(type = "recurring", cronExpression = "0 9 * * *"),
    )
    private val library = ScheduleLibraryState(
        isLoading = false,
        fleet = true,
        agentFilter = "a1",
        schedules = listOf(native),
        crons = listOf(CronTask(id = "c2", agentId = "a2"), CronTask(id = "shared", agentId = null)),
        runHistory = mapOf(
            "s1" to listOf(RunRecord("s1", at, true)),
            "c2" to listOf(RunRecord("c2", at, true)),
            "shared" to listOf(RunRecord("shared", at, true)),
        ),
    )

    @Test
    fun aFilteredFleetShowsOnlyItsAgentsRunHistory() {
        val state = SchedulesPageState(library = library)
        assertEquals(setOf("s1", "shared"), state.scopedRunHistory().keys)
        assertEquals(library.runHistory, state.copy(library = library.copy(agentFilter = null)).scopedRunHistory())
    }

    @Test
    fun aHiddenAgentsCronIsNotTheTargetOfAVisibleRow() {
        val state = SchedulesPageState(library = library)
        assertEquals(null, state.deleteTarget("c2"))
        assertFalse(state.canTogglePause(CronTaskId("c2")))
    }

    @Test
    fun theFleetCannotPauseACronWithNoAgentToWriteThrough() {
        val state = SchedulesPageState(library = library.copy(agentFilter = null))
        assertFalse(state.canTogglePause(CronTaskId("shared")))
        assertTrue(state.canTogglePause(CronTaskId("c2")))
    }
}
