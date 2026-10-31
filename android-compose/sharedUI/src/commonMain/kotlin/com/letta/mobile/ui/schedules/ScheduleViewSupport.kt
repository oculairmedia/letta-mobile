package com.letta.mobile.ui.schedules

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.avatar.core.model.MascotIdentity
import com.letta.mobile.data.schedules.CalendarBand
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A schedule's colour on every calendar view: its agent's mascot identity colour, so an agent reads
 * the same here as in the rail and chat. A host that knows the colour a user picked provides it
 * through [LocalCalendarAccent]; otherwise it is the agent's seeded mascot colour (what the mascot
 * shows until someone picks one). A schedule with no agent takes the theme's primary.
 */
@Composable
fun scheduleAccent(agentId: String?): Color {
    if (agentId == null) return MaterialTheme.colorScheme.primary
    return LocalCalendarAccent.current(agentId) ?: Color(MascotIdentity.seeded(agentId).argb)
}

/** The colour of a run's status dot (the run detail, the history). */
@Composable
fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.Done -> MaterialTheme.customColors.successColor
    RunStatus.Failed -> MaterialTheme.colorScheme.error
    RunStatus.Running, RunStatus.Next -> MaterialTheme.customColors.runningColor
    RunStatus.Skipped, RunStatus.Paused -> MaterialTheme.customColors.onSurfaceMutedColor
    RunStatus.Unrecorded, RunStatus.Upcoming -> MaterialTheme.colorScheme.outline
}

/** The glyph a run's block carries for its outcome; null for a run still to come. */
@Composable
fun outcomeBadge(status: RunStatus): CalendarBadge? = when (status) {
    RunStatus.Done -> CalendarBadge(CalendarGlyphs.Succeeded, MaterialTheme.customColors.successColor, ScheduleRunText.outcome(status))
    RunStatus.Failed -> CalendarBadge(CalendarGlyphs.Failed, MaterialTheme.colorScheme.error, ScheduleRunText.outcome(status))
    RunStatus.Skipped -> mutedBadge(CalendarGlyphs.Skipped, status)
    RunStatus.Unrecorded -> mutedBadge(CalendarGlyphs.Unknown, status)
    RunStatus.Running -> CalendarBadge(CalendarGlyphs.Running, MaterialTheme.customColors.runningColor, ScheduleRunText.outcome(status))
    RunStatus.Next, RunStatus.Upcoming, RunStatus.Paused -> null
}

@Composable
private fun mutedBadge(icon: ImageVector, status: RunStatus) =
    CalendarBadge(icon, MaterialTheme.customColors.onSurfaceMutedColor, ScheduleRunText.outcome(status))

/** How a run's block reads: past runs recede, the next one stands out, a paused one fades. */
fun RunStatus.tone(): CalendarTone = when (this) {
    RunStatus.Next, RunStatus.Running -> CalendarTone.Highlighted
    RunStatus.Upcoming -> CalendarTone.Upcoming
    RunStatus.Paused -> CalendarTone.Faded
    RunStatus.Done, RunStatus.Failed, RunStatus.Skipped, RunStatus.Unrecorded -> CalendarTone.Past
}

/** A run as a calendar block; [showTime] puts its start before the name. */
@Composable
fun runEventSpec(run: ScheduleRun, zone: TimeZone, now: Instant, showTime: Boolean = true): CalendarEventSpec = CalendarEventSpec(
    title = run.scheduleName,
    accent = scheduleAccent(run.agentId),
    tone = run.status.tone(),
    leading = if (showTime) ScheduleRunText.shortTime(run.instant, zone) else null,
    badge = outcomeBadge(run.status),
    description = ScheduleRunText.describe(run, zone, now),
)

/** A collapsed high-frequency band as a calendar block: the name, then how many runs it holds. */
@Composable
fun bandEventSpec(band: CalendarBand, cadence: String?): CalendarEventSpec {
    val failed = band.count(RunStatus.Failed)
    val tone = when {
        band.paused -> CalendarTone.Faded
        band.next == null -> CalendarTone.Past
        else -> CalendarTone.Upcoming
    }
    return CalendarEventSpec(
        title = band.scheduleName,
        accent = scheduleAccent(band.agentId),
        tone = tone,
        trailing = "×${band.count}",
        badge = if (failed > 0) CalendarBadge(CalendarGlyphs.Failed, MaterialTheme.colorScheme.error, "$failed failed") else null,
        description = ScheduleRunText.describeBand(band, cadence),
    )
}

/** The words the calendar views use for runs and days, pure. */
object ScheduleRunText {
    private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** What happened, or will: "Succeeded", "No run record", "Paused". */
    fun outcome(status: RunStatus): String = when (status) {
        RunStatus.Done -> "Succeeded"
        RunStatus.Failed -> "Failed"
        RunStatus.Skipped -> "Skipped"
        RunStatus.Unrecorded -> "No run record"
        RunStatus.Running -> "Running"
        RunStatus.Next -> "Next run"
        RunStatus.Upcoming -> "Scheduled"
        RunStatus.Paused -> "Paused"
    }

    /** The agenda's trailing label: a countdown for the next run, else the outcome. */
    fun trailing(run: ScheduleRun, now: Instant): String =
        if (run.status == RunStatus.Next) ScheduleFormat.relative(now, run.instant) else outcome(run.status)

    /** `9:00`, `12:30`: a block's start on a grid whose gutter already says AM / PM. */
    fun shortTime(instant: Instant, zone: TimeZone): String {
        val t = instant.toLocalDateTime(zone)
        val h12 = if (t.hour % HALF_DAY == 0) HALF_DAY else t.hour % HALF_DAY
        return "$h12:${ScheduleFormat.pad2(t.minute)}"
    }

    /** A band's window over two lines: `9:00 AM–` / `5:45 PM`. */
    fun window(firstMinute: Int, lastMinute: Int): String = "${clock(firstMinute)}–\n${clock(lastMinute)}"

    /** A minute of the day as `9:00 AM`. */
    fun clock(minuteOfDay: Int): String = ScheduleFormat.clockLabel(minuteOfDay / MINUTES, minuteOfDay % MINUTES)

    fun describe(run: ScheduleRun, zone: TimeZone, now: Instant): String {
        val countdown = if (run.status.isPast) "" else ", ${ScheduleFormat.relative(now, run.instant)}"
        return "${run.scheduleName}, ${ScheduleFormat.dateLabel(run.instant, zone)} ${ScheduleFormat.timeOfDay(run.instant, zone)}, " +
            outcome(run.status) + countdown
    }

    fun describeBand(band: CalendarBand, cadence: String?): String =
        "${band.scheduleName}, ${band.count} runs from ${clock(band.firstMinute)} to ${clock(band.lastMinute)}" +
            (cadence?.let { ", $it" } ?: "")

    /** "Today", "Tomorrow", "Yesterday", else "Friday, Oct 30". */
    fun dayHeading(date: LocalDate, today: LocalDate): String = when ((date.toEpochDays() - today.toEpochDays()).toInt()) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> "${WEEKDAYS[date.dayOfWeek.isoDayNumber - 1]}, ${ScheduleFormat.monthShort(date.month.ordinal + 1)} ${date.day}"
    }

    /** "Wednesday, October 28", for a day's accessibility label. */
    fun longDate(date: LocalDate): String =
        "${WEEKDAYS[date.dayOfWeek.isoDayNumber - 1]}, ${ScheduleFormat.monthShort(date.month.ordinal + 1)} ${date.day}"

    fun runCount(count: Int): String = if (count == 1) "1 run" else "$count runs"

    private const val HALF_DAY = 12
    private const val MINUTES = 60
}
