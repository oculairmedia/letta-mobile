package com.letta.mobile.data.schedules

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ScheduleCalendarTest {
    private val newYork = TimeZone.of("America/New_York")
    private val utc = TimeZone.UTC

    private fun def(id: String, cron: String, zone: TimeZone = newYork, active: Boolean = true) =
        ScheduleDef(id = id, name = id, cron = cron, oneShotAt = null, active = active, zone = zone, agentId = "agent-$id")

    private fun days(defs: List<ScheduleDef>, dates: List<LocalDate>, now: Instant, zone: TimeZone = newYork) =
        ScheduleCalendar.days(defs, dates, CalendarQuery(ScheduleClock(now, zone)))

    private val wednesday = LocalDate(2026, 10, 28)
    private val noonWednesday = Instant.parse("2026-10-28T16:00:00Z")

    // --- collapse rule ---------------------------------------------------------------------------

    @Test
    fun everyFifteenMinutesCollapsesToOneBandWithItsCount() {
        val day = days(listOf(def("triage", "*/15 * * * *")), listOf(wednesday), noonWednesday).single()
        assertTrue(day.events.isEmpty())
        val band = day.bands.single()
        assertEquals(96, band.count)
        assertEquals(0, band.firstMinute)
        assertEquals(23 * 60 + 45, band.lastMinute)
        assertEquals("agent-triage", band.agentId)
        assertEquals(96, day.runCount)
    }

    @Test
    fun hourlyCollapsesButEveryTwoHoursStaysOnTheGrid() {
        val day = days(listOf(def("hourly", "0 * * * *"), def("twoHourly", "0 */2 * * *")), listOf(wednesday), noonWednesday).single()
        assertEquals(listOf("hourly"), day.bands.map { it.scheduleId })
        assertEquals(12, day.events.size)
        assertTrue(day.events.all { it.run.scheduleId == "twoHourly" })
    }

    @Test
    fun collapseRuleCountsAndGaps() {
        assertFalse(ScheduleCalendar.collapses(List(12) { it * 120 }))
        assertTrue(ScheduleCalendar.collapses(List(13) { it * 100 }))
        assertTrue(ScheduleCalendar.collapses(listOf(540, 550, 560)), "three runs inside an hour collapse")
        assertFalse(ScheduleCalendar.collapses(listOf(540, 550)), "two close runs stay timed and share lanes")
    }

    // --- lanes -----------------------------------------------------------------------------------

    @Test
    fun overlappingEventsSplitIntoLanesAndClustersReset() {
        val run = { id: String -> ScheduleRun(id, id, noonWednesday, RunStatus.Upcoming) }
        val laid = ScheduleCalendar.layoutLanes(
            listOf(CalendarEvent(run("b"), 550), CalendarEvent(run("a"), 540), CalendarEvent(run("c"), 585)),
        ).associateBy { it.run.scheduleId }
        assertEquals(0 to 2, laid.getValue("a").lane to laid.getValue("a").lanes)
        assertEquals(1 to 2, laid.getValue("b").lane to laid.getValue("b").lanes)
        assertEquals(0 to 1, laid.getValue("c").lane to laid.getValue("c").lanes, "9:45 starts after 9:00–9:40 ends")
    }

    // --- time zones ------------------------------------------------------------------------------

    @Test
    fun aUtcScheduleMovesAnHourOnTheViewersGridAcrossDst() {
        val lunch = def("lunch", "30 12 * * *", zone = utc)
        val dates = listOf(LocalDate(2026, 10, 31), LocalDate(2026, 11, 1))
        val minutes = days(listOf(lunch), dates, noonWednesday).map { it.events.single().minuteOfDay }
        assertEquals(listOf(8 * 60 + 30, 7 * 60 + 30), minutes, "12:30 UTC is 8:30 EDT, then 7:30 EST")
    }

    @Test
    fun localSchedulesFireOnceOnDstSwitchDays() {
        val spring = days(listOf(def("sweep", "30 2 * * *")), listOf(LocalDate(2026, 3, 8)), noonWednesday).single()
        assertEquals(1, spring.events.size, "2:30 does not exist on spring-forward day; it still runs once")
        val fall = days(listOf(def("sweep", "30 1 * * *")), listOf(LocalDate(2026, 11, 1)), noonWednesday).single()
        assertEquals(1, fall.events.size, "1:30 happens twice on fall-back day; the schedule runs once")
        assertEquals(90, fall.events.single().minuteOfDay)
    }

    // --- outcomes --------------------------------------------------------------------------------

    @Test
    fun pastSlotsAreUnrecordedUnlessTheBackendSaysOtherwise() {
        val standup = def("standup", "0 9 * * *").copy(
            lastFiredAt = Instant.parse("2026-10-27T13:00:00Z"),
            missedAt = Instant.parse("2026-10-26T13:00:00Z"),
        )
        val failed = RunRecord("standup", Instant.parse("2026-10-25T13:00:00Z"), succeeded = false)
        val dates = (25..29).map { LocalDate(2026, 10, it) }
        val query = CalendarQuery(ScheduleClock(noonWednesday, newYork), history = mapOf("standup" to listOf(failed)))
        val statuses = ScheduleCalendar.days(listOf(standup), dates, query).map { it.events.single().run.status }
        assertEquals(
            listOf(RunStatus.Failed, RunStatus.Skipped, RunStatus.Done, RunStatus.Unrecorded, RunStatus.Next),
            statuses,
        )
    }

    @Test
    fun pausedSchedulesShowOnlyFutureSlotsAndCanBeFilteredOut() {
        val paused = def("paused", "0 9 * * *", active = false)
        val dates = (27..29).map { LocalDate(2026, 10, it) }
        val shown = days(listOf(paused), dates, noonWednesday)
        assertEquals(listOf(0, 0, 1), shown.map { it.events.size })
        assertEquals(RunStatus.Paused, shown.last().events.single().run.status)
        val query = CalendarQuery(ScheduleClock(noonWednesday, newYork), includePaused = false)
        val hidden = ScheduleCalendar.days(listOf(paused), dates, query)
        assertTrue(hidden.all { it.runCount == 0 })
    }

    @Test
    fun cronTaskStatusAndTimestampsReachTheDef() {
        val task = CronTask(
            id = "c", agentId = "a", cron = "0 9 * * *", recurring = true, timezone = "UTC",
            status = "paused", lastFiredAt = "2026-10-27T09:00:00Z", fireCount = 4,
        )
        val def = with(ScheduleProjection) { task.toScheduleDef(utc, noonWednesday) }
        assertFalse(def.active)
        assertEquals("a", def.agentId)
        assertEquals(Instant.parse("2026-10-27T09:00:00Z"), def.lastFiredAt)
        assertEquals(4, def.fireCount)
    }

    // --- ranges ----------------------------------------------------------------------------------

    @Test
    fun monthCoversWholeWeeksAndRangesPage() {
        val october = CalendarRange.month(wednesday)
        assertEquals(LocalDate(2026, 9, 28), october.first())
        assertEquals(LocalDate(2026, 11, 1), october.last())
        assertEquals(35, october.size)
        assertEquals(LocalDate(2026, 11, 28), CalendarRange.shift(SchedulesView.Month, wednesday, 1))
        assertEquals(LocalDate(2026, 11, 4), CalendarRange.shift(SchedulesView.Week, wednesday, 1))
        assertEquals("October 2026", CalendarRange.label(SchedulesView.Month, wednesday))
        assertEquals("Oct 28 – Nov 3", CalendarRange.label(SchedulesView.Agenda, wednesday))
        assertEquals("October 26 – November 1, 2026", CalendarRange.label(SchedulesView.Week, wednesday))
        assertEquals("Oct 26 – Nov 1", CalendarRange.label(SchedulesView.Week, wednesday, compact = true))
    }

    @Test
    fun zoneLabelNamesTheOffsetInForce() {
        assertEquals("UTC−4 · America/New_York", CalendarLabels.zoneLabel(newYork, noonWednesday))
        val afterDst = Instant.parse("2026-11-02T12:00:00Z")
        assertEquals("UTC−5 · America/New_York", CalendarLabels.zoneLabel(newYork, afterDst))
        assertEquals("UTC+5:30 · Asia/Kolkata", CalendarLabels.zoneLabel(TimeZone.of("Asia/Kolkata"), noonWednesday))
        assertEquals("UTC", CalendarLabels.zoneLabel(utc, noonWednesday))
    }

    @Test
    fun hourLabelsReadTwelveHour() {
        assertEquals(listOf("12 AM", "9 AM", "12 PM", "5 PM"), listOf(0, 9, 12, 17).map(CalendarLabels::hourLabel))
    }

    @Test
    fun anEmptySlotStartsAWeeklyDraftAtThatHour() {
        val draft = SchedulesPageModel.draftAt(LocalDate(2026, 10, 29), 15)
        assertEquals("0 15 * * 4", draft.expression)
    }

    // --- recurrence copy -------------------------------------------------------------------------

    @Test
    fun recurrenceReadsAsASentence() {
        val cases = mapOf(
            "* * * * *" to "Every minute",
            "*/15 * * * *" to "Every 15 minutes",
            "*/15 9-17 * * 1-5" to "Every 15 minutes, 9:00 AM to 5:45 PM on weekdays",
            "0 * * * *" to "Every hour",
            "5 * * * *" to "Every hour at :05",
            "0 */2 * * *" to "Every 2 hours",
            "0 9-17 * * *" to "Every hour, 9:00 AM to 5:00 PM",
            "0 9 * * *" to "Every day at 9:00 AM",
            "30 8 * * 1-5" to "Every weekday at 8:30 AM",
            "0 10 * * 6,0" to "Every Saturday and Sunday at 10:00 AM",
            "0 16 * * 5" to "Every Friday at 4:00 PM",
            "0 9 * * 1,3,5" to "Every Mon, Wed and Fri at 9:00 AM",
            "0 9,13,17 * * *" to "Every day at 9:00 AM, 1:00 PM and 5:00 PM",
            "0 9 1 * *" to "Monthly on the 1st at 9:00 AM",
            "0 9 1,15 * *" to "Monthly on the 1st and 15th at 9:00 AM",
            "0 15 29 10 *" to "Every year on Oct 29 at 3:00 PM",
            "0 9 1 * 1" to "0 9 1 * 1",
        )
        cases.forEach { (cron, phrase) -> assertEquals(phrase, CronSchedule.describe(CronSchedule.parse(cron)!!), cron) }
    }
}
