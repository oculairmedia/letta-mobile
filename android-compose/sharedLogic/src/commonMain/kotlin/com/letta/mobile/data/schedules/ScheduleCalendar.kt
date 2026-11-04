package com.letta.mobile.data.schedules

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.schedules.leases.Lease
import com.letta.mobile.data.schedules.leases.LeaseSpan
import com.letta.mobile.data.schedules.leases.leaseSpan
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A timed run on a calendar day, in overlap [lane] of [lanes] side-by-side columns. */
@Immutable
data class CalendarEvent(
    val run: ScheduleRun,
    /** Minutes since the viewer's local midnight (0..1439). */
    val minuteOfDay: Int,
    val lane: Int = 0,
    val lanes: Int = 1,
)

/**
 * One high-frequency schedule's runs on one day, collapsed into a single band so a `*`/15 job
 * does not flood the grid or the agenda. [firstMinute]..[lastMinute] is the window it fires in.
 */
@Immutable
data class CalendarBand(
    val scheduleId: String,
    val scheduleName: String,
    val agentId: String?,
    val runs: List<ScheduleRun>,
    val firstMinute: Int,
    val lastMinute: Int,
) {
    val count: Int get() = runs.size

    fun count(status: RunStatus): Int = runs.count { it.status == status }

    /** The band's first run that has not happened yet, or null when the day is over. */
    val next: ScheduleRun? get() = runs.firstOrNull { !it.status.isPast }

    /** True when every run in the band belongs to a paused schedule. */
    val paused: Boolean get() = runs.all { it.status == RunStatus.Paused }
}

/** A day's entries in time order: a timed [Event] or a collapsed [Band]. */
sealed interface CalendarEntry {
    val minuteOfDay: Int

    data class Event(val event: CalendarEvent) : CalendarEntry {
        override val minuteOfDay: Int get() = event.minuteOfDay
    }

    /** A portion of a lease on this day. */
    data class LeaseEntry(val span: LeaseSpan) : CalendarEntry {
        override val minuteOfDay: Int get() = span.startMinute.toInt()
    }

    data class Band(val band: CalendarBand) : CalendarEntry {
        override val minuteOfDay: Int get() = band.firstMinute
    }
}

/** One calendar day: its timed events (laned) and its collapsed high-frequency bands. */
@Immutable
data class CalendarDay(
    val date: LocalDate,
    val events: List<CalendarEvent>,
    val bands: List<CalendarBand>,
    /** Lease portions visible on this date. */
    val leases: List<LeaseSpan> = emptyList(),
) {
    /** Every run that day, collapsed ones included. */
    val runCount: Int get() = events.size + bands.sumOf { it.count }

    /** Runs that day the backend recorded as failed. */
    val failedCount: Int get() = events.count { it.run.status == RunStatus.Failed } + bands.sumOf { it.count(RunStatus.Failed) }

    /** Every entry in time order, a band at its first run. */
    val entries: List<CalendarEntry>
        get() = (
            events.map { CalendarEntry.Event(it) } +
                bands.map { CalendarEntry.Band(it) } +
                leases.map { CalendarEntry.LeaseEntry(it) }
            ).sortedBy { it.minuteOfDay }

    /** The agents with runs that day, first-run first; null for a schedule with no agent. */
    val agentIds: List<String?> get() = entries.filterNot { it is CalendarEntry.LeaseEntry }.map { entryAgent(it) }.distinct()

    private fun entryAgent(entry: CalendarEntry): String? = when (entry) {
        is CalendarEntry.Event -> entry.event.run.agentId
        is CalendarEntry.Band -> entry.band.agentId
        is CalendarEntry.LeaseEntry -> entry.span.lease.owner
    }
}

/** What a calendar projection is computed against. [includePaused] false leaves paused schedules out. */
@Immutable
data class CalendarQuery(
    val clock: ScheduleClock,
    val history: Map<String, List<RunRecord>> = emptyMap(),
    val includePaused: Boolean = true,
    /** Include recorded non-cron wakes in the agenda without placing them on calendar grids. */
    val includeUnscheduled: Boolean = false,
    /** Read-only leases to project into each day. */
    val leases: List<Lease> = emptyList(),
)

/**
 * Projects schedules onto calendar days in the viewer's zone. Each schedule's cron is expanded in
 * its own zone ([ScheduleDef.zone]) and the instants are bucketed into the viewer's local days, so a
 * UTC schedule moves an hour on the viewer's grid across a DST switch, exactly as it really fires.
 */
object ScheduleCalendar {
    /** How long an event block reads as on a time grid; runs closer than this share a row in lanes. */
    const val EVENT_SPAN_MINUTES = 30

    /** A schedule firing more often than this in a day collapses to a band. */
    const val COLLAPSE_RUNS_PER_DAY = 12

    /** Three or more runs of one schedule closer than this collapse to a band too. */
    const val COLLAPSE_MIN_GAP_MINUTES = 60

    private const val COLLAPSE_MIN_RUNS_FOR_GAP = 3

    fun days(defs: List<ScheduleDef>, dates: List<LocalDate>, query: CalendarQuery): List<CalendarDay> {
        if (dates.isEmpty()) return emptyList()
        val zone = query.clock.zone
        val now = query.clock.now
        val start = dates.first().atStartOfDayIn(zone)
        val end = dates.last().plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val globalNext = ScheduleProjection.globalNextInstant(defs, now)
        val placed = defs.filter { query.includePaused || it.active }.flatMap { def ->
            occurrences(def, start, end, now).map { instant ->
                val run = ScheduleProjection.classify(def, instant, now, instant == globalNext, query.history)
                Placed(run, instant.toLocalDateTime(zone).let { it.date to it.hour * MINUTES_PER_HOUR + it.minute })
            }
        }
        val unscheduled = unscheduled(defs, query, start, end)
        val byDate = (placed + unscheduled).groupBy { it.at.first }
        return dates.map { date ->
            day(date, byDate[date].orEmpty()).copy(leases = query.leases.mapNotNull { leaseSpan(it, date, zone) })
        }
    }

    private fun unscheduled(
        defs: List<ScheduleDef>, query: CalendarQuery, start: Instant, end: Instant,
    ): List<Placed> {
        if (!query.includeUnscheduled) return emptyList()
        return defs.filter { query.includePaused || it.active }.flatMap { def ->
            query.history[def.id].orEmpty().filter {
                it.source != null && it.source != "cron" && it.instant in start..<end && it.instant <= query.clock.now
            }.map { record ->
                val local = record.instant.toLocalDateTime(query.clock.zone)
                val run = ScheduleRun(def.id, record.scheduleName ?: def.name, record.instant, record.status, record.durationMillis,
                    record.exitCode, record.error, def.agentId, record.reason, record.source, record.recordId)
                Placed(run, local.date to local.hour * MINUTES_PER_HOUR + local.minute)
            }
        }
    }

    /** True when runs at these minutes of one day should collapse into a band. */
    fun collapses(minutes: List<Int>): Boolean {
        if (minutes.size > COLLAPSE_RUNS_PER_DAY) return true
        if (minutes.size < COLLAPSE_MIN_RUNS_FOR_GAP) return false
        return minutes.sorted().zipWithNext().any { (a, b) -> b - a < COLLAPSE_MIN_GAP_MINUTES }
    }

    /**
     * Places [events] in side-by-side lanes: events whose [EVENT_SPAN_MINUTES] spans overlap form a
     * cluster, each takes the first lane free at its start, and every event in a cluster reports the
     * cluster's lane count so their widths match.
     */
    fun layoutLanes(events: List<CalendarEvent>): List<CalendarEvent> {
        val sorted = events.sortedWith(compareBy({ it.minuteOfDay }, { it.run.scheduleName }))
        val out = ArrayList<CalendarEvent>(sorted.size)
        var cluster = ArrayList<CalendarEvent>()
        var laneEnds = ArrayList<Int>()
        var clusterEnd = Int.MIN_VALUE
        fun flush() {
            out += cluster.map { it.copy(lanes = laneEnds.size) }
            cluster = ArrayList()
            laneEnds = ArrayList()
        }
        for (event in sorted) {
            if (event.minuteOfDay >= clusterEnd) flush()
            val end = event.minuteOfDay + EVENT_SPAN_MINUTES
            val lane = laneEnds.indexOfFirst { it <= event.minuteOfDay }.takeIf { it >= 0 } ?: laneEnds.size
            if (lane == laneEnds.size) laneEnds += end else laneEnds[lane] = end
            cluster += event.copy(lane = lane)
            clusterEnd = maxOf(clusterEnd, end)
        }
        flush()
        return out
    }

    /**
     * A schedule's instants in `[start, end)`. A paused schedule shows only its future slots (it did
     * not run while paused); an active one shows past slots too, so their outcomes can be read.
     */
    private fun occurrences(def: ScheduleDef, start: Instant, end: Instant, now: Instant): List<Instant> =
        if (def.active) {
            ScheduleProjection.runsBetween(def, start, end)
        } else {
            ScheduleProjection.runsBetween(def.copy(active = true), start, end).filter { it > now }
        }

    private class Placed(val run: ScheduleRun, val at: Pair<LocalDate, Int>) {
        val minute: Int get() = at.second
    }

    private fun day(date: LocalDate, placed: List<Placed>): CalendarDay {
        val (wakes, scheduled) = placed.partition { it.run.source != null && it.run.source != "cron" }
        val bySchedule = scheduled.groupBy { it.run.scheduleId }.values
        val (collapsed, timed) = bySchedule.partition { runs -> collapses(runs.map { it.minute }) }
        val bands = collapsed.map { runs ->
            val sorted = runs.sortedBy { it.minute }
            val first = sorted.first().run
            CalendarBand(
                scheduleId = first.scheduleId,
                scheduleName = first.scheduleName,
                agentId = first.agentId,
                runs = sorted.map { it.run },
                firstMinute = sorted.first().minute,
                lastMinute = sorted.last().minute,
            )
        }.sortedBy { it.firstMinute }
        val events = layoutLanes((timed.flatten() + wakes).map { CalendarEvent(it.run, it.minute) })
        return CalendarDay(date, events, bands)
    }

    private const val MINUTES_PER_HOUR = 60
}

/** The dates a calendar view spans around an anchor date, and how it pages. */
object CalendarRange {
    private const val DAYS_PER_WEEK = 7

    /** Agenda days shown at once. */
    const val AGENDA_DAYS = 7

    fun dates(view: SchedulesView, anchor: LocalDate): List<LocalDate> = when (view) {
        SchedulesView.Week -> week(anchor)
        SchedulesView.Month -> month(anchor)
        else -> (0 until AGENDA_DAYS).map { anchor.plus(it, DateTimeUnit.DAY) }
    }

    /** Monday-first week containing [anchor]. */
    fun week(anchor: LocalDate): List<LocalDate> {
        val monday = ScheduleProjection.mondayOf(anchor)
        return (0 until DAYS_PER_WEEK).map { monday.plus(it, DateTimeUnit.DAY) }
    }

    /** Whole Monday-first weeks covering [anchor]'s month (five or six rows). */
    fun month(anchor: LocalDate): List<LocalDate> {
        val first = LocalDate(anchor.year, anchor.month, 1)
        val last = first.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY)
        val start = ScheduleProjection.mondayOf(first)
        val end = ScheduleProjection.mondayOf(last).plus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY)
        val count = (end.toEpochDays() - start.toEpochDays()).toInt() + 1
        return (0 until count).map { start.plus(it, DateTimeUnit.DAY) }
    }

    /** The anchor [steps] pages away: a week, a month or an agenda page. */
    fun shift(view: SchedulesView, anchor: LocalDate, steps: Int): LocalDate = when (view) {
        SchedulesView.Month -> anchor.plus(steps, DateTimeUnit.MONTH)
        else -> anchor.plus(steps * DAYS_PER_WEEK, DateTimeUnit.DAY)
    }

    /**
     * "October 26 – November 1, 2026", "October 2026" or "Oct 28 – Nov 3". [compact] (a phone's
     * header) shortens the week to "Oct 26 – Nov 1".
     */
    fun label(view: SchedulesView, anchor: LocalDate, compact: Boolean = false): String = when (view) {
        SchedulesView.Week -> if (compact) {
            val monday = ScheduleProjection.mondayOf(anchor)
            "${shortDate(monday)} – ${shortDate(monday.plus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY))}"
        } else {
            SchedulesPageModel.weekRangeLabel(ScheduleProjection.mondayOf(anchor))
        }
        SchedulesView.Month -> "${SchedulesPageModel.fullMonth(anchor.month.ordinal + 1)} ${anchor.year}"
        else -> {
            val end = anchor.plus(AGENDA_DAYS - 1, DateTimeUnit.DAY)
            "${shortDate(anchor)} – ${shortDate(end)}"
        }
    }

    private fun shortDate(date: LocalDate): String = "${ScheduleFormat.monthShort(date.month.ordinal + 1)} ${date.day}"
}
