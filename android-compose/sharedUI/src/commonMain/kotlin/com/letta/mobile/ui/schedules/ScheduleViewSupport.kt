package com.letta.mobile.ui.schedules

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.time.Instant

/** Geometry and labels shared by the schedule views (week grid, agenda, timeline) and their hosts. */
val HOUR_HEIGHT = 44.dp
const val GRID_HOURS = 24
const val HOUR_LABEL_STEP = 3
const val WEEK_GRID_MAX_PER_DAY = 4

@Composable
fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.Done -> MaterialTheme.customColors.successColor
    RunStatus.Failed -> MaterialTheme.colorScheme.error
    RunStatus.Running, RunStatus.Next -> MaterialTheme.customColors.runningColor
    RunStatus.Upcoming -> MaterialTheme.colorScheme.outline
}

@Composable
fun agendaStatusLabel(run: ScheduleRun, now: Instant): Pair<String, Color> = when (run.status) {
    RunStatus.Done -> "Ran" to MaterialTheme.customColors.successColor
    RunStatus.Failed -> "Failed" to MaterialTheme.colorScheme.error
    RunStatus.Running -> "running" to MaterialTheme.customColors.runningColor
    RunStatus.Next -> ScheduleFormat.relative(now, run.instant) to MaterialTheme.customColors.runningColor
    RunStatus.Upcoming -> "scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
}

fun monthDayLabel(date: LocalDate): String =
    "${ScheduleFormat.monthShort(date.month.ordinal + 1)} ${date.day}"

fun dayHeading(date: LocalDate, today: LocalDate): String {
    val delta = (date.toEpochDays() - today.toEpochDays()).toInt()
    return when (delta) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> "${fullWeekday(date.dayOfWeek.isoDayNumber)}, ${monthDayLabel(date)}"
    }
}

fun fullWeekday(iso: Int): String =
    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[(iso - 1).coerceIn(0, 6)]
