@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.schedules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CalendarDay
import com.letta.mobile.data.schedules.CalendarEvent
import com.letta.mobile.data.schedules.CalendarQuery
import com.letta.mobile.data.schedules.ScheduleCalendar
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.data.schedules.runs.cronRunRecords
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneDefaults
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPage
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageOptions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageState
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageTags
import java.io.File
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.time.Instant

class ScheduleRunReasonTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val date = LocalDate(2026, 11, 2)
    private val def = ScheduleDef("task-A", "daily", null, null, true, agentId = "agent-A")

    private fun fixtureRecords() = run {
        val path = "docs/reference/contracts/run.record/merged-cron-runs-v1.json"
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, path).isFile }
        val example = Json.parseToJsonElement(File(root, path).readText()).jsonObject
        val page = example.getValue("response").jsonObject.getValue("result").jsonObject.getValue("page").jsonObject
        cronRunRecords(AppServerInboundFrame.CronRunsResponse("test", true, page))
    }

    @Test
    fun realMapperToRenderedHistoryContainsReason() = runComposeUiTest {
        val records = fixtureRecords()
        val summary = ScheduleProjection.history(listOf(def), now, TimeZone.UTC, mapOf(def.id to records))
        setContent { MaterialTheme { HistoryView(summary) } }
        onNodeWithText("woke: requested by operator", substring = true).assertExists()
    }

    @Test
    fun agendaRendersReasonOnRealRow() = runComposeUiTest {
        val records = fixtureRecords()
        val query = CalendarQuery(ScheduleClock(now, TimeZone.UTC), mapOf(def.id to records), includeUnscheduled = true)
        val days = ScheduleCalendar.days(listOf(def), listOf(date), query)
        val actions = ScheduleCalendarActions({}, {}, {}, {}, null)
        val params = ScheduleCalendarParams(days, date, date, ScheduleClock(now, TimeZone.UTC), emptyMap(), actions)
        setContent { MaterialTheme { ScheduleAgendaList(params) } }
        onNodeWithText("woke: requested by operator", substring = true).assertExists()
    }

    @Test
    fun aWakeWithoutAScheduleIsOnlyInAgendaAndHistory() = runComposeUiTest {
        val library = ScheduleLibraryState(
            isLoading = false,
            selectedAgentId = "agent-A",
            runHistory = mapOf(def.id to fixtureRecords()),
        )
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLettaHost provides LettaHost.Pointer.copy(sheet = LettaPaneDefaults.Dialog)) {
                    Box(Modifier.width(760.dp).height(900.dp)) {
                        SchedulesPage(
                            SchedulesPageState(library = library, focusedAgentId = "agent-A"),
                            SchedulesPageActions(),
                            options = SchedulesPageOptions(
                                initialView = SchedulesView.Agenda,
                                fixedClock = ScheduleClock(now, TimeZone.UTC),
                            ),
                        )
                    }
                }
            }
        }
        onNodeWithText("woke: requested by operator", substring = true).assertExists()
        onNodeWithTag(SchedulesPageTags.tab(SchedulesView.Rules)).performClick()
        onNodeWithTag(SchedulesPageTags.rule(def.id)).assertDoesNotExist()
        onNodeWithTag(SchedulesPageTags.tab(SchedulesView.Week)).performClick()
        onNodeWithTag(SchedulesPageTags.EMPTY).assertExists()
        onNodeWithTag(SchedulesPageTags.tab(SchedulesView.Month)).performClick()
        onNodeWithTag(SchedulesPageTags.EMPTY).assertExists()
        onNodeWithTag(SchedulesPageTags.tab(SchedulesView.History)).performClick()
        onNodeWithText("woke: requested by operator", substring = true).assertExists()
    }

    @Test
    fun noReasonKeepsTheExistingAgendaRowText() = runComposeUiTest {
        val record = fixtureRecords().first { it.reason == null }
        val history = ScheduleProjection.history(listOf(def), now, TimeZone.UTC, mapOf(def.id to listOf(record)))
        val run = history.schedules.single().recentRuns.single()
        val yesterday = LocalDate(2026, 11, 1)
        val day = CalendarDay(yesterday, listOf(CalendarEvent(run, 0)), emptyList())
        val actions = ScheduleCalendarActions({}, {}, {}, {}, null)
        setContent {
            MaterialTheme {
                val params = ScheduleCalendarParams(
                    listOf(day), date, yesterday, ScheduleClock(now, TimeZone.UTC), mapOf(def.id to "Every day"), actions,
                )
                ScheduleAgendaList(params)
            }
        }
        onNodeWithText("12:00 AM", useUnmergedTree = true).assertTextEquals("12:00 AM")
        onNodeWithText("daily", useUnmergedTree = true).assertTextEquals("daily")
        onNodeWithText("Every day", useUnmergedTree = true).assertTextEquals("Every day")
        onNodeWithText("Succeeded", useUnmergedTree = true).assertTextEquals("Succeeded")
    }
}
