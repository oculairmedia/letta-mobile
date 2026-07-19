package com.letta.mobile.desktop.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.WeekCalendar
import com.kizitonwose.calendar.compose.weekcalendar.rememberWeekCalendarState
import com.kizitonwose.calendar.core.WeekDay
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.letta.mobile.data.schedules.CronSchedule
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal data class AgendaViewParams(
    val defs: List<ScheduleDef>,
    val selectedDate: LocalDate,
    val today: LocalDate,
    val now: Instant,
    val zone: TimeZone,
    val onSelectDate: (LocalDate) -> Unit,
    val onRunClick: (ScheduleRun) -> Unit,
)

internal data class AgendaRowParams(
    val run: ScheduleRun,
    val subtitle: String?,
    val now: Instant,
    val zone: TimeZone,
    val onClick: () -> Unit,
)

@Composable
internal fun AgendaView(params: AgendaViewParams) {
    val agenda = remember(params.defs, params.selectedDate, params.now) {
        ScheduleProjection.agenda(params.defs, params.selectedDate, params.now, params.zone)
    }
    val completed = agenda.runs.count { it.status == RunStatus.Done }
    val cadenceById = remember(params.defs) { cadenceLabels(params.defs) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        AgendaMonthHeader(params)
        AgendaDateStrip(params.selectedDate, params.today, params.onSelectDate)
        Spacer(Modifier.height(16.dp))
        Text(
            dayHeading(params.selectedDate, params.today),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "${agenda.runs.size} scheduled runs · $completed completed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
        )
        Spacer(Modifier.height(12.dp))
        AgendaRunList(
            AgendaRunListParams(
                runs = agenda.runs,
                cadenceById = cadenceById,
                now = params.now,
                zone = params.zone,
                onRunClick = params.onRunClick,
            ),
        )
    }
}

private data class AgendaRunListParams(
    val runs: List<ScheduleRun>,
    val cadenceById: Map<String, String>,
    val now: Instant,
    val zone: TimeZone,
    val onRunClick: (ScheduleRun) -> Unit,
)

private fun cadenceLabels(defs: List<ScheduleDef>): Map<String, String> =
    defs.associate { def ->
        def.id to (def.cron?.let { c -> CronSchedule.parse(c)?.let(CronSchedule::describe) } ?: "One-time")
    }

@Composable
private fun AgendaMonthHeader(params: AgendaViewParams) {
    val isToday = params.selectedDate == params.today
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${ScheduleFormat.monthShort(params.selectedDate.month.ordinal + 1)} ${params.selectedDate.year}".uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(
                    if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .clickable { params.onSelectDate(params.today) }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                "Today",
                style = MaterialTheme.typography.labelMedium,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgendaRunList(params: AgendaRunListParams) {
    if (params.runs.isEmpty()) {
        Text(
            "No runs scheduled for this day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
        )
        return
    }
    LazyColumn {
        items(items = params.runs, key = { it.scheduleId + it.instant.toString() }) { run ->
            AgendaRow(
                AgendaRowParams(
                    run = run,
                    subtitle = params.cadenceById[run.scheduleId],
                    now = params.now,
                    zone = params.zone,
                    onClick = { params.onRunClick(run) },
                ),
            )
        }
    }
}

@Composable
internal fun AgendaDateStrip(selectedDate: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val state = rememberWeekCalendarState(
        startDate = today.minus(14, DateTimeUnit.DAY),
        endDate = today.plus(120, DateTimeUnit.DAY),
        firstVisibleWeekDate = selectedDate,
        firstDayOfWeek = firstDayOfWeekFromLocale(),
    )
    WeekCalendar(state = state, dayContent = { day: WeekDay ->
        AgendaDayCell(day.date, selectedDate, today, onSelect)
    })
}

@Composable
internal fun AgendaDayCell(date: LocalDate, selectedDate: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val selected = date == selectedDate
    Column(
        Modifier.padding(2.dp).clickable { onSelect(date) }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            ScheduleFormat.weekdayShort(date.dayOfWeek).take(1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.day}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = agendaDayNumberColor(selected, date == today),
            )
        }
    }
}

@Composable
private fun agendaDayNumberColor(selected: Boolean, isToday: Boolean): Color = when {
    selected -> MaterialTheme.colorScheme.onPrimary
    isToday -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
internal fun AgendaRow(params: AgendaRowParams) {
    val ldt = params.run.instant.toLocalDateTime(params.zone)
    val time = "${ScheduleFormat.pad2(ldt.hour)}:${ScheduleFormat.pad2(ldt.minute)}"
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = params.onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp).padding(top = 12.dp),
        )
        AgendaStatusRail(status = params.run.status)
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                params.run.scheduleName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!params.subtitle.isNullOrBlank()) {
                Text(
                    params.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.customColors.onSurfaceMutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val (label, color) = agendaStatusLabel(params.run, params.now)
        Text(
            if (params.run.status == RunStatus.Done) "Ran $time" else label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun AgendaStatusRail(status: RunStatus) {
    Box(Modifier.width(24.dp).fillMaxHeight()) {
        Box(
            Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        )
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(10.dp).clip(CircleShape)
                .background(statusColor(status)),
        )
    }
}
