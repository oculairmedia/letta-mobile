package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.ScheduledMessage
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Status of a single materialized run (drives the color in every view).
 *  - [Done] / [Failed]   — a past run, succeeded or missed/errored.
 *  - [Running]           — firing right now.
 *  - [Next]              — the soonest upcoming run (amber emphasis).
 *  - [Upcoming]          — a future run beyond the next one.
 */
enum class RunStatus { Done, Failed, Running, Next, Upcoming }

/**
 * Platform-neutral view of a schedule, decoupled from the [CronTask] wire
 * model so the projection logic is trivially testable and transplantable.
 * Built from a [CronTask] via [toScheduleDef].
 */
data class ScheduleDef(
    val id: String,
    val name: String,
    /** Recurring cron expression, or `null` for a one-shot. */
    val cron: String?,
    /** One-shot fire time, or `null` for a recurring schedule. */
    val oneShotAt: Instant?,
    val active: Boolean,
    val lastFiredAt: Instant? = null,
    val fireCount: Int = 0,
    val zone: TimeZone = TimeZone.UTC,
) {
    val recurring: Boolean get() = cron != null
}

/** A historical run record, when a per-run backend feed is available. */
data class RunRecord(
    val scheduleId: String,
    val instant: Instant,
    val succeeded: Boolean,
    val durationMillis: Long? = null,
    val exitCode: Int? = null,
    val error: String? = null,
)

/** A single materialized run placed on a view. */
data class ScheduleRun(
    val scheduleId: String,
    val scheduleName: String,
    val instant: Instant,
    val status: RunStatus,
    val durationMillis: Long? = null,
    val exitCode: Int? = null,
    val error: String? = null,
)

/** Agenda: the time-ordered runs of one day. */
data class AgendaDay(
    val date: LocalDate,
    val runs: List<ScheduleRun>,
)

/** A run placed on the Week time-grid. */
data class WeekRun(
    val run: ScheduleRun,
    /** 0 = first day of the grid week .. 6. */
    val dayIndex: Int,
    /** Minutes since local midnight (0..1439) — the row position. */
    val minuteOfDay: Int,
)

data class WeekGrid(
    val weekStart: LocalDate,
    val days: List<LocalDate>,
    val runs: List<WeekRun>,
)

/** A tick on a Timeline swimlane. */
data class TimelineTick(
    val instant: Instant,
    val status: RunStatus,
)

data class TimelineLane(
    val scheduleId: String,
    val scheduleName: String,
    val cadence: String,
    val ticks: List<TimelineTick>,
)

data class Timeline(
    val start: Instant,
    val end: Instant,
    val lanes: List<TimelineLane>,
)

/** History: per-schedule reliability + recent runs. */
data class ScheduleReliability(
    val scheduleId: String,
    val scheduleName: String,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    /** Most-recent-last outcome squares: true=ok, false=fail, null=unknown/pending. */
    val squares: List<Boolean?>,
    val recentRuns: List<ScheduleRun>,
) {
    val successRate: Double? get() = if (total == 0) null else succeeded.toDouble() / total
}

data class HistorySummary(
    val overallSuccessRate: Double?,
    val totalRuns: Int,
    val schedules: List<ScheduleReliability>,
)

/**
 * Pure projections of a set of schedules into the four Phase-7 views. Future
 * runs are computed from the cron expression; past runs come from an optional
 * [RunRecord] overlay (a real per-run backend feed) and otherwise degrade to
 * what the schedule itself records (lastFiredAt / fireCount).
 */
object ScheduleProjection {

    private const val RUNNING_WINDOW_SECONDS = 90L
    private const val DEFAULT_RELIABILITY_SQUARES = 12

    /**
     * Adapt a [CronTask] (the slim `/v1/crons` wire shape) into a platform-
     * neutral [ScheduleDef]. The cron is evaluated in the task's own time zone
     * when it declares one, so absolute fire instants are correct regardless of
     * the viewer's zone; the UI then formats those instants in the viewer's
     * zone. The slim wire shape carries no run history, so fireCount/lastFired
     * default empty and the History view degrades accordingly.
     */
    fun CronTask.toScheduleDef(viewerZone: TimeZone, now: Instant): ScheduleDef {
        val taskZone = timezone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: viewerZone
        val displayName = name?.takeIf { it.isNotBlank() }
            ?: description?.takeIf { it.isNotBlank() }
            ?: prompt?.takeIf { it.isNotBlank() }?.take(40)
            ?: "Schedule"
        val rawCron = cron?.takeIf { it.isNotBlank() }
        // A one-off is persisted as a fully-specified cron with no year, and the
        // slim /v1/crons shape carries no separate timestamp. Keeping the cron
        // (Codex review #16) made the projection treat it as a yearly recurring
        // schedule (Codex review #16b). Resolve it to a single bounded instant
        // instead: cron=null + oneShotAt = the next occurrence from [now], so it
        // materializes exactly once and never recurs.
        return if (recurring || rawCron == null) {
            ScheduleDef(
                id = id,
                name = displayName,
                cron = rawCron,
                oneShotAt = null,
                active = true,
                zone = taskZone,
            )
        } else {
            ScheduleDef(
                id = id,
                name = displayName,
                cron = null,
                oneShotAt = CronSchedule.parse(rawCron)?.let { CronSchedule.nextRun(it, now, taskZone) },
                active = true,
                zone = taskZone,
            )
        }
    }

    fun toScheduleDefs(crons: List<CronTask>, zone: TimeZone, now: Instant): List<ScheduleDef> =
        crons.map { it.toScheduleDef(zone, now) }

    /**
     * Adapt a native schedule-admin [ScheduledMessage] (the
     * `/v1/agents/{id}/schedule` shape) into a [ScheduleDef]. Backends that
     * serve the native route but no `/v1/crons` expose schedules only here, so
     * a surface that projects from crons alone hides them (Codex review #5).
     * For one-shots the fire instant comes from `scheduled_at` (Unix epoch
     * seconds) or, when absent, the `next_scheduled_time` ISO timestamp
     * fallback (Codex review #6) — otherwise a one-time schedule would carry
     * neither cron nor oneShotAt and never materialize. The wire shape carries
     * no per-schedule time zone, so the viewer's zone is used.
     */
    fun ScheduledMessage.toScheduleDef(viewerZone: TimeZone): ScheduleDef {
        val recurring = schedule.type == "recurring"
        val label = message.messages.firstOrNull()?.content?.trim()?.takeIf { it.isNotBlank() }
        val oneShot = if (recurring) {
            null
        } else {
            schedule.scheduledAt?.let { Instant.fromEpochSeconds(it.toLong()) }
                ?: nextScheduledTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
        }
        return ScheduleDef(
            id = id,
            name = label?.take(40) ?: "Schedule",
            cron = if (recurring) schedule.cronExpression?.takeIf { it.isNotBlank() } else null,
            oneShotAt = oneShot,
            active = true,
            zone = viewerZone,
        )
    }

    fun toScheduleDefsFromNative(schedules: List<ScheduledMessage>, zone: TimeZone): List<ScheduleDef> =
        schedules.map { it.toScheduleDef(zone) }

    /** The single next run instant for [def] strictly after [now]. */
    fun nextRun(def: ScheduleDef, now: Instant): Instant? = when {
        // A paused schedule has no upcoming run (Codex review #15).
        !def.active -> null
        def.cron != null -> CronSchedule.parse(def.cron)?.let { CronSchedule.nextRun(it, now, def.zone) }
        def.oneShotAt != null -> def.oneShotAt.takeIf { it > now }
        else -> null
    }

    /** Materialize a schedule's runs within `[start, end)`. */
    private fun runsBetween(def: ScheduleDef, start: Instant, end: Instant): List<Instant> = when {
        // Paused schedules don't project future runs (Codex review #15). Past
        // runs still surface via the History overlay, which doesn't go through
        // this path.
        !def.active -> emptyList()
        def.cron != null ->
            CronSchedule.parse(def.cron)?.let { CronSchedule.runsBetween(it, start, end, def.zone) }.orEmpty()
        def.oneShotAt != null -> listOfNotNull(def.oneShotAt.takeIf { it >= start && it < end })
        else -> emptyList()
    }

    private fun classify(
        def: ScheduleDef,
        instant: Instant,
        now: Instant,
        isGlobalNext: Boolean,
        history: Map<String, List<RunRecord>>,
    ): ScheduleRun {
        val record = history[def.id]?.firstOrNull { sameMinute(it.instant, instant, def.zone) }
        val status = when {
            record != null -> if (record.succeeded) RunStatus.Done else RunStatus.Failed
            (instant - now).inWholeSeconds in -RUNNING_WINDOW_SECONDS..RUNNING_WINDOW_SECONDS && def.active ->
                RunStatus.Running
            instant <= now -> RunStatus.Done
            isGlobalNext -> RunStatus.Next
            else -> RunStatus.Upcoming
        }
        return ScheduleRun(
            scheduleId = def.id,
            scheduleName = def.name,
            instant = instant,
            status = status,
            durationMillis = record?.durationMillis,
            exitCode = record?.exitCode,
            error = record?.error,
        )
    }

    private fun sameMinute(a: Instant, b: Instant, zone: TimeZone): Boolean {
        val la = a.toLocalDateTime(zone)
        val lb = b.toLocalDateTime(zone)
        return la.year == lb.year && la.month == lb.month && la.day == lb.day &&
            la.hour == lb.hour && la.minute == lb.minute
    }

    /** Agenda for [date]: every run that day, time-ordered, status-tagged. */
    fun agenda(
        defs: List<ScheduleDef>,
        date: LocalDate,
        now: Instant,
        zone: TimeZone,
        history: Map<String, List<RunRecord>> = emptyMap(),
    ): AgendaDay {
        val start = date.atStartOfDayIn(zone)
        val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val globalNext = globalNextInstant(defs, now)
        val runs = defs.flatMap { def ->
            runsBetween(def, start, end).map { instant ->
                classify(def, instant, now, isGlobalNext = instant == globalNext, history)
            }
        }.sortedBy { it.instant }
        return AgendaDay(date, runs)
    }

    /** Week time-grid starting at [weekStart] (a Monday by convention). */
    fun week(
        defs: List<ScheduleDef>,
        weekStart: LocalDate,
        now: Instant,
        zone: TimeZone,
        history: Map<String, List<RunRecord>> = emptyMap(),
    ): WeekGrid {
        val days = (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) }
        val start = weekStart.atStartOfDayIn(zone)
        val end = weekStart.plus(7, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val globalNext = globalNextInstant(defs, now)
        val weekRuns = defs.flatMap { def ->
            runsBetween(def, start, end).map { instant ->
                val ldt = instant.toLocalDateTime(zone)
                val dayIndex = ldt.date.toEpochDays().toInt() - weekStart.toEpochDays().toInt()
                WeekRun(
                    run = classify(def, instant, now, isGlobalNext = instant == globalNext, history),
                    dayIndex = dayIndex.coerceIn(0, 6),
                    minuteOfDay = ldt.hour * 60 + ldt.minute,
                )
            }
        }
        return WeekGrid(weekStart, days, weekRuns)
    }

    /**
     * Timeline swimlanes spanning [days] days from [startDate] (defaults to the
     * day containing [now]). The caller passes the displayed week's start so the
     * lanes line up with the column headers; [now] still drives run
     * classification (Done/Running/Next). Without an explicit [startDate] the
     * lanes were always anchored to today, so navigating weeks left the ticks
     * mismatched against the headers (Codex/CodeRabbit review #1).
     */
    fun timeline(
        defs: List<ScheduleDef>,
        now: Instant,
        zone: TimeZone,
        startDate: LocalDate = now.toLocalDateTime(zone).date,
        days: Int = 7,
        history: Map<String, List<RunRecord>> = emptyMap(),
    ): Timeline {
        val start = startDate.atStartOfDayIn(zone)
        val end = startDate.plus(days, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val globalNext = globalNextInstant(defs, now)
        val lanes = defs.map { def ->
            val ticks = runsBetween(def, start, end).map { instant ->
                TimelineTick(
                    instant = instant,
                    status = classify(def, instant, now, isGlobalNext = instant == globalNext, history).status,
                )
            }
            TimelineLane(
                scheduleId = def.id,
                scheduleName = def.name,
                cadence = def.cron?.let { CronSchedule.parse(it)?.let(CronSchedule::describe) }
                    ?: "One-time",
                ticks = ticks,
            )
        }
        return Timeline(start, end, lanes)
    }

    /**
     * History/reliability. Uses the [history] overlay when present; otherwise
     * degrades to the schedule's own fireCount (treated as succeeded, since the
     * wire model records no per-run failures) so the view still has signal.
     */
    fun history(
        defs: List<ScheduleDef>,
        now: Instant,
        zone: TimeZone,
        history: Map<String, List<RunRecord>> = emptyMap(),
        squares: Int = DEFAULT_RELIABILITY_SQUARES,
    ): HistorySummary {
        val perSchedule = defs.map { def ->
            val records = history[def.id]?.sortedBy { it.instant }.orEmpty()
            if (records.isNotEmpty()) {
                val succeeded = records.count { it.succeeded }
                val failed = records.size - succeeded
                val recent = records.takeLast(squares)
                ScheduleReliability(
                    scheduleId = def.id,
                    scheduleName = def.name,
                    total = records.size,
                    succeeded = succeeded,
                    failed = failed,
                    squares = recent.map { it.succeeded },
                    recentRuns = recent.takeLast(5).reversed().map {
                        ScheduleRun(
                            scheduleId = def.id,
                            scheduleName = def.name,
                            instant = it.instant,
                            status = if (it.succeeded) RunStatus.Done else RunStatus.Failed,
                            durationMillis = it.durationMillis,
                            exitCode = it.exitCode,
                            error = it.error,
                        )
                    },
                )
            } else {
                // Degraded: only an aggregate fire count is known.
                val count = def.fireCount
                ScheduleReliability(
                    scheduleId = def.id,
                    scheduleName = def.name,
                    total = count,
                    succeeded = count,
                    failed = 0,
                    squares = List(count.coerceAtMost(squares)) { true },
                    recentRuns = def.lastFiredAt?.let {
                        listOf(
                            ScheduleRun(def.id, def.name, it, RunStatus.Done),
                        )
                    }.orEmpty(),
                )
            }
        }
        val totalRuns = perSchedule.sumOf { it.total }
        val totalOk = perSchedule.sumOf { it.succeeded }
        return HistorySummary(
            overallSuccessRate = if (totalRuns == 0) null else totalOk.toDouble() / totalRuns,
            totalRuns = totalRuns,
            schedules = perSchedule,
        )
    }

    private fun globalNextInstant(defs: List<ScheduleDef>, now: Instant): Instant? =
        defs.mapNotNull { nextRun(it, now) }.minOrNull()

    /** The Monday on or before [date] (week grids start on Monday). */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
}
