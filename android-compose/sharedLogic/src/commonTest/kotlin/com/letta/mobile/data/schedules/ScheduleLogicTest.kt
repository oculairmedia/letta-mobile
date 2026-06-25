package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleLogicTest {
    private val utc = TimeZone.UTC
    // 2026-06-24T12:00:00Z is a Wednesday.
    private val now = Instant.parse("2026-06-24T12:00:00Z")

    // --- CronSchedule -------------------------------------------------------

    @Test
    fun parsesAndRejects() {
        assertNotNull(CronSchedule.parse("0 9 * * *"))
        assertNotNull(CronSchedule.parse("*/15 * * * *"))
        assertNotNull(CronSchedule.parse("30 8 * * 1,3,5"))
        assertNull(CronSchedule.parse("not a cron"))
        assertNull(CronSchedule.parse("0 9 * *")) // too few fields
        assertNull(CronSchedule.parse("99 9 * * *")) // out of range
        assertFalse(CronSchedule.isValid("60 0 * * *"))
    }

    @Test
    fun dailyNextRunCrossesToNextDay() {
        val expr = CronSchedule.parse("0 9 * * *")!!
        val next = CronSchedule.nextRun(expr, now, utc)!!
        assertEquals(Instant.parse("2026-06-25T09:00:00Z"), next)
    }

    @Test
    fun everyFifteenMinutesProducesQuarterHours() {
        val expr = CronSchedule.parse("*/15 * * * *")!!
        val runs = CronSchedule.nextRuns(expr, now, utc, count = 2)
        assertEquals(
            listOf(Instant.parse("2026-06-24T12:15:00Z"), Instant.parse("2026-06-24T12:30:00Z")),
            runs,
        )
    }

    @Test
    fun weekdayOnlyNeverFiresOnWeekend() {
        val expr = CronSchedule.parse("0 9 * * 1-5")!!
        val runs = CronSchedule.nextRuns(expr, now, utc, count = 10)
        assertTrue(runs.isNotEmpty())
        runs.forEach { instant ->
            val iso = instant.toLocalDateTime(utc).dayOfWeek.isoDayNumber
            assertTrue(iso in 1..5, "expected a weekday but got iso=$iso")
        }
    }

    @Test
    fun describesCommonCadences() {
        assertEquals("Every minute", CronSchedule.describe(CronSchedule.parse("* * * * *")!!))
        assertEquals("Every 15 minutes", CronSchedule.describe(CronSchedule.parse("*/15 * * * *")!!))
        assertEquals("Every day at 9:00 AM", CronSchedule.describe(CronSchedule.parse("0 9 * * *")!!))
        assertEquals("Weekdays at 8:30 AM", CronSchedule.describe(CronSchedule.parse("30 8 * * 1-5")!!))
        assertEquals("Every hour at :05", CronSchedule.describe(CronSchedule.parse("5 * * * *")!!))
    }

    @Test
    fun runsBetweenBoundsAreHalfOpen() {
        val expr = CronSchedule.parse("0 * * * *")!! // top of every hour
        val start = Instant.parse("2026-06-24T12:00:00Z")
        val end = Instant.parse("2026-06-24T15:00:00Z")
        val runs = CronSchedule.runsBetween(expr, start, end, utc)
        // 12:00 included (>= start), 15:00 excluded (< end).
        assertEquals(
            listOf(
                Instant.parse("2026-06-24T12:00:00Z"),
                Instant.parse("2026-06-24T13:00:00Z"),
                Instant.parse("2026-06-24T14:00:00Z"),
            ),
            runs,
        )
    }

    // --- CronBuilder --------------------------------------------------------

    @Test
    fun builderProducesExpressions() {
        assertEquals(
            "*/15 * * * *",
            CronBuilder.toExpression(CronBuilderState(CronCadence.EveryNMinutes, intervalMinutes = 15)),
        )
        assertEquals(
            "0 9 * * *",
            CronBuilder.toExpression(CronBuilderState(CronCadence.Daily, minute = 0, hour = 9)),
        )
        assertEquals(
            "30 8 * * 1,2,3,4,5",
            CronBuilder.toExpression(
                CronBuilderState(CronCadence.Weekly, minute = 30, hour = 8, daysOfWeek = setOf(1, 2, 3, 4, 5)),
            ),
        )
        assertNull(
            CronBuilder.toExpression(CronBuilderState(CronCadence.Weekly, daysOfWeek = emptySet())),
        )
    }

    @Test
    fun builderRoundTripsThroughExpression() {
        val daily = CronBuilder.fromExpression("0 9 * * *")
        assertEquals(CronCadence.Daily, daily.cadence)
        assertEquals(9, daily.hour)
        assertEquals(0, daily.minute)

        val weekly = CronBuilder.fromExpression("30 8 * * 1,2,3,4,5")
        assertEquals(CronCadence.Weekly, weekly.cadence)
        assertEquals(setOf(1, 2, 3, 4, 5), weekly.daysOfWeek)

        val every = CronBuilder.fromExpression("*/15 * * * *")
        assertEquals(CronCadence.EveryNMinutes, every.cadence)
        assertEquals(15, every.intervalMinutes)

        val custom = CronBuilder.fromExpression("0 0 1 1 *")
        assertEquals(CronCadence.Custom, custom.cadence)
    }

    @Test
    fun builderPreviewDescribesAndLists() {
        val state = CronBuilderState(CronCadence.Daily, minute = 0, hour = 9)
        assertEquals("Every day at 9:00 AM", CronBuilder.preview(state))
        val runs = CronBuilder.previewRuns(state, now, utc, count = 2)
        assertEquals(
            listOf(Instant.parse("2026-06-25T09:00:00Z"), Instant.parse("2026-06-26T09:00:00Z")),
            runs,
        )
    }

    // --- ScheduleProjection -------------------------------------------------

    private fun dailyDef(id: String, expr: String, name: String = id) =
        ScheduleDef(id = id, name = name, cron = expr, oneShotAt = null, active = true, zone = utc)

    @Test
    fun agendaTagsTheSoonestRunAsNext() {
        val defs = listOf(dailyDef("a", "0 9 * * *"), dailyDef("b", "0 18 * * *"))
        // Before either of today's runs, so today's 09:00 is the global next.
        val early = Instant.parse("2026-06-24T08:00:00Z")
        val today = early.toLocalDateTime(utc).date
        val agenda = ScheduleProjection.agenda(defs, today, early, utc)
        assertEquals(2, agenda.runs.size)
        // 09:00 sorts first and is the soonest run anywhere → Next.
        assertEquals(RunStatus.Next, agenda.runs.first().status)
        assertEquals("a", agenda.runs.first().scheduleId)
        // 18:00 is upcoming but not the global next.
        assertEquals(RunStatus.Upcoming, agenda.runs.last().status)
    }

    @Test
    fun weekGridHasSevenDaysAndPlacesRuns() {
        val defs = listOf(dailyDef("a", "0 9 * * *"))
        val monday = ScheduleProjection.mondayOf(now.toLocalDateTime(utc).date)
        val grid = ScheduleProjection.week(defs, monday, now, utc)
        assertEquals(7, grid.days.size)
        // A daily 9am schedule fires once per day → 7 placements at minute 540.
        assertEquals(7, grid.runs.size)
        assertTrue(grid.runs.all { it.minuteOfDay == 9 * 60 })
        assertTrue(grid.runs.all { it.dayIndex in 0..6 })
    }

    @Test
    fun timelineLaneDescribesCadence() {
        val defs = listOf(dailyDef("a", "0 9 * * *", name = "Morning brief"))
        val timeline = ScheduleProjection.timeline(defs, now, utc, days = 7)
        assertEquals(1, timeline.lanes.size)
        assertEquals("Every day at 9:00 AM", timeline.lanes.first().cadence)
        // 7-day window, daily → 7 ticks.
        assertEquals(7, timeline.lanes.first().ticks.size)
    }

    @Test
    fun historyUsesRecordsWhenPresentElseDegradesToFireCount() {
        val defs = listOf(
            ScheduleDef("a", "A", cron = "0 9 * * *", oneShotAt = null, active = true, fireCount = 3, zone = utc),
        )
        // Degraded path: no records → fireCount treated as successes.
        val degraded = ScheduleProjection.history(defs, now, utc)
        assertEquals(3, degraded.totalRuns)
        assertEquals(1.0, degraded.overallSuccessRate)

        // Overlay path: explicit records including a failure.
        val records = mapOf(
            "a" to listOf(
                RunRecord("a", Instant.parse("2026-06-22T09:00:00Z"), succeeded = true, durationMillis = 1200),
                RunRecord("a", Instant.parse("2026-06-23T09:00:00Z"), succeeded = false, error = "boom"),
            ),
        )
        val withHistory = ScheduleProjection.history(defs, now, utc, history = records)
        val rel = withHistory.schedules.first()
        assertEquals(2, rel.total)
        assertEquals(1, rel.failed)
        assertEquals(listOf(true, false), rel.squares)
        assertEquals(0.5, withHistory.overallSuccessRate)
    }

    // --- Review-fix regressions (letta-mobile-9qqa5) ------------------------

    @Test
    fun pausedScheduleProjectsNoRuns() {
        val active = dailyDef("on", "0 9 * * *")
        val paused = active.copy(id = "off", active = false)
        // A paused schedule has no upcoming run; an active one still does (#15).
        assertNull(ScheduleProjection.nextRun(paused, now))
        assertNotNull(ScheduleProjection.nextRun(active, now))
        // Neither Agenda nor Week materialize the paused schedule's runs.
        val today = now.toLocalDateTime(utc).date
        val agenda = ScheduleProjection.agenda(listOf(active, paused), today, now, utc)
        assertTrue(agenda.runs.all { it.scheduleId == "on" })
        val monday = ScheduleProjection.mondayOf(today)
        val week = ScheduleProjection.week(listOf(active, paused), monday, now, utc)
        assertTrue(week.runs.all { it.run.scheduleId == "on" })
    }

    @Test
    fun oneShotCronTaskStillMaterializes() {
        // recurring=false one-off: the adapter keeps the cron so the task can
        // still produce a run instead of vanishing (#16).
        val task = CronTask(id = "once", cron = "30 14 25 12 *", recurring = false)
        val def = ScheduleProjection.toScheduleDefs(listOf(task), utc).single()
        assertEquals("30 14 25 12 *", def.cron)
        assertNotNull(ScheduleProjection.nextRun(def, now))
    }

    @Test
    fun leapDayScheduleResolvesWithinLookahead() {
        // 0 0 29 2 * fires only on Feb 29 — the next can be ~4 years out, past
        // the old 400-day window (#14).
        val expr = CronSchedule.parse("0 0 29 2 *")!!
        val next = CronSchedule.nextRun(expr, now, utc)
        assertNotNull(next)
        val ldt = next.toLocalDateTime(utc)
        assertEquals(2, ldt.month.ordinal + 1)
        assertEquals(29, ldt.day)
    }

    @Test
    fun weeklyBuilderRejectsOutOfRangeWeekday() {
        // Out-of-range ISO weekday → null instead of a bad cron string (#13).
        assertNull(
            CronBuilder.toExpression(
                CronBuilderState(CronCadence.Weekly, minute = 0, hour = 9, daysOfWeek = setOf(8)),
            ),
        )
        assertNotNull(
            CronBuilder.toExpression(
                CronBuilderState(CronCadence.Weekly, minute = 0, hour = 9, daysOfWeek = setOf(1, 7)),
            ),
        )
    }

    @Test
    fun timelineAnchorsToStartDate() {
        // Lanes span the requested week, not today's (#1).
        val defs = listOf(dailyDef("a", "0 9 * * *"))
        val nextWeekStart = ScheduleProjection.mondayOf(now.toLocalDateTime(utc).date).plus(7, DateTimeUnit.DAY)
        val timeline = ScheduleProjection.timeline(defs, now, utc, startDate = nextWeekStart, days = 7)
        val ticks = timeline.lanes.single().ticks
        assertEquals(7, ticks.size)
        ticks.forEach { tick ->
            val date = tick.instant.toLocalDateTime(utc).date
            assertTrue(date >= nextWeekStart && date < nextWeekStart.plus(7, DateTimeUnit.DAY))
        }
    }

    @Test
    fun nativeScheduleAdapterSurfacesRecurringSchedule() {
        // Native schedule-admin schedules project alongside crons (#5).
        val native = ScheduledMessage(
            id = "r",
            agentId = "agent",
            message = SchedulePayload(messages = listOf(ScheduleMessage(content = "Daily digest", role = "user"))),
            schedule = ScheduleDefinition(type = "recurring", cronExpression = "0 9 * * *"),
        )
        val def = ScheduleProjection.toScheduleDefsFromNative(listOf(native), utc).single()
        assertEquals("0 9 * * *", def.cron)
        assertEquals("Daily digest", def.name)
        assertNotNull(ScheduleProjection.nextRun(def, now))
    }

    @Test
    fun nativeOneShotFallsBackToNextScheduledTime() {
        // One-time native schedule with only next_scheduled_time (no scheduled_at)
        // still materializes via the ISO fallback (#6).
        val fire = Instant.parse("2030-01-01T00:00:00Z")
        val native = ScheduledMessage(
            id = "o",
            agentId = "agent",
            message = SchedulePayload(messages = listOf(ScheduleMessage(content = "One off", role = "user"))),
            nextScheduledTime = fire.toString(),
            schedule = ScheduleDefinition(type = "one_time"),
        )
        val def = ScheduleProjection.toScheduleDefsFromNative(listOf(native), utc).single()
        assertNull(def.cron)
        assertEquals(fire, def.oneShotAt)
        assertEquals(fire, ScheduleProjection.nextRun(def, now))
    }

    // --- ScheduleFormat -----------------------------------------------------

    @Test
    fun formatsClockRelativeAndDuration() {
        assertEquals("9:00 AM", ScheduleFormat.clockLabel(9, 0))
        assertEquals("12:00 PM", ScheduleFormat.clockLabel(12, 0))
        assertEquals("12:05 AM", ScheduleFormat.clockLabel(0, 5))
        assertEquals("in 2h 14m", ScheduleFormat.relative(now, Instant.parse("2026-06-24T14:14:00Z")))
        assertEquals("5m ago", ScheduleFormat.relative(now, Instant.parse("2026-06-24T11:55:00Z")))
        assertEquals("in 3d", ScheduleFormat.relative(now, Instant.parse("2026-06-27T12:00:00Z")))
        assertEquals("1m 12s", ScheduleFormat.duration(72_000))
        assertEquals("340ms", ScheduleFormat.duration(340))
        assertEquals("1h 5m", ScheduleFormat.duration(3_900_000))
    }
}
