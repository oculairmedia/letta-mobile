package com.letta.mobile.ui.schedules

import com.letta.mobile.data.schedules.CalendarDay
import com.letta.mobile.data.schedules.CalendarEvent
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleRun
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The agenda's lines: headings, the now line between past and future today, empty days. */
class ScheduleAgendaItemsTest {
    private val today = LocalDate(2026, 10, 28)
    private val tomorrow = LocalDate(2026, 10, 29)

    private fun event(id: String, minute: Int) =
        CalendarEvent(ScheduleRun(id, id, Instant.parse("2026-10-28T00:00:00Z"), RunStatus.Upcoming), minute)

    @Test
    fun theNowLineSitsBetweenWhatRanAndWhatIsComing() {
        val days = listOf(
            CalendarDay(today, listOf(event("early", 540), event("late", 900)), emptyList()),
            CalendarDay(tomorrow, emptyList(), emptyList()),
        )
        val items = agendaItems(days, today, nowMinute = 620)
        val kinds = items.map {
            when (it) {
                is AgendaItem.Heading -> "heading"
                is AgendaItem.Empty -> "empty"
                is AgendaItem.Now -> "now"
                is AgendaItem.Entry -> "entry"
            }
        }
        assertEquals(listOf("heading", "entry", "now", "entry", "heading", "empty"), kinds)
    }

    @Test
    fun afterTheLastRunTheNowLineEndsTheDay() {
        val items = agendaItems(listOf(CalendarDay(today, listOf(event("early", 540)), emptyList())), today, nowMinute = 1200)
        assertTrue(items.last() is AgendaItem.Now)
        assertEquals(1, items.count { it is AgendaItem.Now })
    }

    @Test
    fun runTextIsHonestAboutUnknownOutcomes() {
        assertEquals("No run record", ScheduleRunText.outcome(RunStatus.Unrecorded))
        assertEquals("9:05", ScheduleRunText.shortTime(Instant.parse("2026-10-28T09:05:00Z"), kotlinx.datetime.TimeZone.UTC))
        assertEquals("12:30", ScheduleRunText.shortTime(Instant.parse("2026-10-28T12:30:00Z"), kotlinx.datetime.TimeZone.UTC))
        assertEquals("Tomorrow", ScheduleRunText.dayHeading(tomorrow, today))
        assertEquals("Friday, Oct 30", ScheduleRunText.dayHeading(LocalDate(2026, 10, 30), today))
    }
}
