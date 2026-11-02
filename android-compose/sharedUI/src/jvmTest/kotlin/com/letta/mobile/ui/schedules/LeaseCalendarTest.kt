@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.schedules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CalendarDay
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.RunRecord
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneDefaults
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPage
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageOptions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageState
import com.letta.mobile.data.schedules.leases.Lease
import com.letta.mobile.data.schedules.leases.LeaseState
import com.letta.mobile.data.schedules.leases.leaseSpan
import com.letta.mobile.data.schedules.runs.leaseRecords
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class LeaseCalendarTest {
    private val spanHeightTolerancePx = 1.5f
    private val date = LocalDate(2026, 11, 2)
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private fun lease(id: String, state: LeaseState, start: String, end: String) = Lease(
        id, "agent-A", "Lease $id", Instant.parse(start), Instant.parse(end), state,
    )

    private fun unknownLeaseFromFixture(): Lease {
        val path = "docs/reference/contracts/run.record/success-examples-v1.json"
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, path).isFile }
        val cases = Json.parseToJsonElement(File(root, path).readText()).jsonObject.getValue("cases").jsonArray
        val response = cases.first { it.jsonObject["name"] == JsonPrimitive("lease-list") }.jsonObject.getValue("response")
        val frame = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(AppServerInboundFrame.AdminRpcResponse.serializer(), response)
        val result = frame.result!!.jsonObject
        val row = result.getValue("leases").jsonArray.single().jsonObject
        val unknown = JsonObject(row + ("state" to JsonPrimitive("future-state")))
        val modified = JsonObject(result + ("leases" to JsonArray(listOf(unknown))))
        return leaseRecords(frame.copy(result = modified)).single().copy(
            startedAt = Instant.parse("2026-11-02T12:00:00Z"), dueAt = Instant.parse("2026-11-02T13:00:00Z"),
        )
    }

    @Test
    fun eachStateHasItsOwnBadgeAndMeasuredDuration() = runComposeUiTest {
        val leases = listOf(
            lease("a", LeaseState.ACTIVE, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z"),
            lease("e", LeaseState.EXPIRED, "2026-11-02T11:00:00Z", "2026-11-02T12:30:00Z"),
            lease("r", LeaseState.RECOVERED, "2026-11-02T12:30:00Z", "2026-11-02T14:30:00Z"),
        )
        var pixelsPerHour = 0f
        val day = CalendarDay(date, emptyList(), emptyList(), leases.map { leaseSpan(it, date, TimeZone.UTC)!! })
        val params = ScheduleCalendarParams(listOf(day), date, date, ScheduleClock(now, TimeZone.UTC), emptyMap(),
            ScheduleCalendarActions({}, {}, {}, {}, null))
        setContent { MaterialTheme {
            pixelsPerHour = with(LocalDensity.current) { CalendarDimens.hourHeight.toPx() }
            Box(Modifier.width(800.dp).height(900.dp)) { ScheduleWeekCalendar(params) }
        } }
        listOf(Triple("a", "Active", 1f), Triple("e", "Expired", 1.5f), Triple("r", "Recovered", 2f)).forEach { (id, badge, hours) ->
            val node = onNodeWithTag("lease-${badge.lowercase()}-$id")
            node.assertExists()
            onNodeWithText(badge, useUnmergedTree = true).assertExists()
            val height = node.fetchSemanticsNode().boundsInRoot.height
            assertTrue(
                kotlin.math.abs(height - hours * pixelsPerHour) <= spanHeightTolerancePx,
                "$id height $height vs ${hours * pixelsPerHour} px (tolerance $spanHeightTolerancePx px)",
            )
        }
    }

    @Test
    fun unknownStateIsAnExplicitNeutralBlock() = runComposeUiTest {
        val lease = unknownLeaseFromFixture()
        val day = CalendarDay(date, emptyList(), emptyList(), listOf(leaseSpan(lease, date, TimeZone.UTC)!!))
        val params = ScheduleCalendarParams(listOf(day), date, date, ScheduleClock(now, TimeZone.UTC), emptyMap(),
            ScheduleCalendarActions({}, {}, {}, {}, null))
        setContent { MaterialTheme { Box(Modifier.width(800.dp).height(900.dp)) { ScheduleWeekCalendar(params) } } }
        onNodeWithTag("lease-unknown-lease-1").assertExists()
        onNodeWithTag("lease-active-lease-1").assertDoesNotExist()
        onNodeWithText("Unknown", useUnmergedTree = true).assertExists()
    }

    @Test
    fun agendaRowShowsLeaseDetailsAndUnknownBadge() = runComposeUiTest {
        val lease = lease("u", LeaseState.UNKNOWN, "2026-11-02T12:00:00Z", "2026-11-02T13:00:00Z")
        setContent { MaterialTheme { LeaseAgendaRow(lease, TimeZone.UTC) {} } }
        onNodeWithTag("lease-row-unknown-u").assertIsDisplayed()
        onNodeWithText("Lease u", useUnmergedTree = true).assertExists()
        onNodeWithText("agent-A", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("1:00 PM", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Unknown", useUnmergedTree = true).assertExists()
    }
    @Test
    fun linkedLeaseOpensAndHighlightsItsRunInAgendaWhileUnlinkedIsSafe() = runComposeUiTest {
        val linked = lease("linked", LeaseState.ACTIVE, "2026-11-02T12:00:00Z", "2026-11-02T13:00:00Z").copy(runId = "run-1")
        val unlinked = lease("unlinked", LeaseState.DONE, "2026-11-02T13:00:00Z", "2026-11-02T14:00:00Z")
        val record = RunRecord("task", now, succeeded = true, source = "human", scheduleName = "Linked run", recordId = "run-1")
        val library = ScheduleLibraryState(isLoading = false, selectedAgentId = "agent-A", leases = listOf(linked, unlinked),
            runHistory = mapOf("task" to listOf(record)))
        setContent { MaterialTheme {
            CompositionLocalProvider(LocalLettaHost provides LettaHost.Pointer.copy(sheet = LettaPaneDefaults.Dialog)) {
                Box(Modifier.width(1100.dp).height(900.dp)) {
                    SchedulesPage(SchedulesPageState(library = library, focusedAgentId = "agent-A"), SchedulesPageActions(),
                        options = SchedulesPageOptions(initialView = SchedulesView.Agenda, fixedClock = ScheduleClock(now, TimeZone.UTC)))
                }
            }
        } }
        onNodeWithTag("lease-row-done-unlinked").performClick()
        onNodeWithTag("lease-row-active-linked").performClick()
        onNodeWithTag("linked-run-run-1").assertIsDisplayed()
    }

}
