package com.letta.mobile.desktop.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.letta.mobile.desktop.components.DesktopChipTab
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.WeekCalendar
import com.kizitonwose.calendar.compose.weekcalendar.rememberWeekCalendarState
import com.kizitonwose.calendar.core.WeekDay
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.letta.mobile.data.schedules.CronBuilder
import com.letta.mobile.data.schedules.CronBuilderState
import com.letta.mobile.data.schedules.CronCadence
import com.letta.mobile.data.schedules.CronSchedule
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopInlineError
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

// --- Week time-grid ---------------------------------------------------------

internal data class WeekViewParams(
    val defs: List<ScheduleDef>,
    val weekStart: LocalDate,
    val today: LocalDate,
    val now: Instant,
    val zone: TimeZone,
    val selectedId: String?,
    val onRunClick: (ScheduleRun) -> Unit,
)

@Composable
internal fun WeekView(params: WeekViewParams) {
    val defs = params.defs
    val weekStart = params.weekStart
    val today = params.today
    val now = params.now
    val zone = params.zone
    val selectedId = params.selectedId
    val onRunClick = params.onRunClick
    val grid = remember(defs, weekStart, now) { ScheduleProjection.week(defs, weekStart, now, zone) }
    val nowMinutes = remember(now, zone) { now.toLocalDateTime(zone).let { it.hour * 60 + it.minute } }
    val todayInWeek = today in grid.days
    val gutter = 56.dp

    // The week grid is for discrete daily/weekly schedules. Sub-daily schedules
    // (hourly and faster) would fill every row into a wall, so — like the
    // mockup — they're excluded here and surfaced in the rail + Timeline view.
    val frequent = remember(grid) {
        grid.runs.groupBy { it.run.scheduleId }.filterValues { it.size > 7 * WEEK_GRID_MAX_PER_DAY }.keys
    }
    val visibleRuns = remember(grid, frequent) { grid.runs.filter { it.run.scheduleId !in frequent } }
    val hiddenCount = remember(frequent) { frequent.size }

    Column(Modifier.fillMaxSize()) {
        // Day header row.
        Row(Modifier.fillMaxWidth().padding(start = gutter, end = 4.dp)) {
            grid.days.forEach { date ->
                val isToday = date == today
                Box(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isToday) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(ScheduleFormat.weekdayShort(date.dayOfWeek), style = MaterialTheme.typography.labelMedium, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${date.day}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (visibleRuns.isEmpty() && hiddenCount > 0) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "$hiddenCount high-frequency schedule${if (hiddenCount == 1) "" else "s"} run every hour — see the Timeline view for their run density.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.customColors.onSurfaceMutedColor,
                )
            }
        }
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (HOUR_HEIGHT * 5).roundToPx() }) }
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT * GRID_HOURS)) {
                // Hour gutter — label every 3 hours, aligned to the gridline.
                Column(Modifier.width(gutter)) {
                    (0 until GRID_HOURS).forEach { h ->
                        Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                            if (h % HOUR_LABEL_STEP == 0) {
                                Text(
                                    "${ScheduleFormat.pad2(h)}:00",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.customColors.onSurfaceMutedColor,
                                    modifier = Modifier.offset(y = (-6).dp).padding(end = 10.dp),
                                )
                            }
                        }
                    }
                }
                // Day columns.
                grid.days.forEachIndexed { dayIndex, date ->
                    val isToday = date == today
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isToday) MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f) else Color.Transparent),
                    ) {
                        // Hour grid lines (stronger every 3 hours).
                        Column(Modifier.fillMaxSize()) {
                            (0 until GRID_HOURS).forEach { h ->
                                Box(
                                    Modifier.height(HOUR_HEIGHT).fillMaxWidth().border(
                                        width = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (h % HOUR_LABEL_STEP == 0) 0.4f else 0.18f),
                                    ),
                                )
                            }
                        }
                        // Left column divider.
                        Box(Modifier.fillMaxHeight().width(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)))
                        // Run bars (discrete daily/weekly schedules only).
                        visibleRuns.filter { it.dayIndex == dayIndex }.forEach { wr ->
                            val emphasized = selectedId == null || wr.run.scheduleId == selectedId
                            WeekRunBar(wr.run, wr.minuteOfDay, emphasized, onClick = { onRunClick(wr.run) })
                        }
                    }
                }
            }
            // Now line across the day columns, with a dot at the gutter edge.
            if (todayInWeek) {
                Row(
                    Modifier.fillMaxWidth().offset(y = HOUR_HEIGHT * (nowMinutes / 60f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(gutter), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.size(8.dp).offset(x = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                    Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
internal fun WeekRunBar(run: ScheduleRun, minuteOfDay: Int, emphasized: Boolean, onClick: () -> Unit) {
    val color = statusColor(run.status)
    val filled = run.status == RunStatus.Done || run.status == RunStatus.Running
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp)
            .offset(y = HOUR_HEIGHT * (minuteOfDay / 60f))
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (filled) color.copy(alpha = if (emphasized) 0.9f else 0.4f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = if (emphasized) 1f else 0.4f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            run.scheduleName,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) MaterialTheme.colorScheme.onSurface else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Agenda -----------------------------------------------------------------

internal data class AgendaViewParams(
    val defs: List<ScheduleDef>,
    val selectedDate: LocalDate,
    val today: LocalDate,
    val now: Instant,
    val zone: TimeZone,
    val onSelectDate: (LocalDate) -> Unit,
    val onRunClick: (ScheduleRun) -> Unit,
)

@Composable
internal fun AgendaView(params: AgendaViewParams) {
    val defs = params.defs
    val selectedDate = params.selectedDate
    val today = params.today
    val now = params.now
    val zone = params.zone
    val onSelectDate = params.onSelectDate
    val onRunClick = params.onRunClick
    val agenda = remember(defs, selectedDate, now) { ScheduleProjection.agenda(defs, selectedDate, now, zone) }
    val completed = agenda.runs.count { it.status == RunStatus.Done }
    val cadenceById = remember(defs) {
        defs.associate { it.id to (it.cron?.let { c -> CronSchedule.parse(c)?.let(CronSchedule::describe) } ?: "One-time") }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ScheduleFormat.monthShort(selectedDate.month.ordinal + 1)} ${selectedDate.year}".uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
            Spacer(Modifier.weight(1f))
            val isToday = selectedDate == today
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelectDate(today) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text("Today", style = MaterialTheme.typography.labelMedium, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AgendaDateStrip(selectedDate, today, onSelectDate)
        Spacer(Modifier.height(16.dp))
        Text(dayHeading(selectedDate, today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text("${agenda.runs.size} scheduled runs · $completed completed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(12.dp))
        if (agenda.runs.isEmpty()) {
            Text("No runs scheduled for this day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
        } else {
            LazyColumn {
                items(items = agenda.runs, key = { it.scheduleId + it.instant.toString() }) { run ->
                    AgendaRow(
                        AgendaRowParams(
                            run = run,
                            subtitle = cadenceById[run.scheduleId],
                            now = now,
                            zone = zone,
                            onClick = { onRunClick(run) },
                        ),
                    )
                }
            }
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
    WeekCalendar(state = state, dayContent = { day: WeekDay -> AgendaDayCell(day.date, selectedDate, today, onSelect) })
}

@Composable
internal fun AgendaDayCell(date: LocalDate, selectedDate: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val selected = date == selectedDate
    Column(
        Modifier.padding(2.dp).clickable { onSelect(date) }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ScheduleFormat.weekdayShort(date.dayOfWeek).take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
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
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    date == today -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

internal data class AgendaRowParams(
    val run: ScheduleRun,
    val subtitle: String?,
    val now: Instant,
    val zone: TimeZone,
    val onClick: () -> Unit,
)

@Composable
internal fun AgendaRow(params: AgendaRowParams) {
    val run = params.run
    val subtitle = params.subtitle
    val now = params.now
    val zone = params.zone
    val onClick = params.onClick
    val ldt = run.instant.toLocalDateTime(zone)
    val time = "${ScheduleFormat.pad2(ldt.hour)}:${ScheduleFormat.pad2(ldt.minute)}"
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp).padding(top = 12.dp))
        // Status dot on a connecting vertical line.
        Box(Modifier.width(24.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
            Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(10.dp).clip(CircleShape).background(statusColor(run.status)))
        }
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(run.scheduleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val (label, color) = agendaStatusLabel(run, now)
        Text(if (run.status == RunStatus.Done) "Ran $time" else label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 12.dp))
    }
}

// --- Timeline swimlanes -----------------------------------------------------

internal data class TimelineViewParams(
    val defs: List<ScheduleDef>,
    val weekStart: LocalDate,
    val today: LocalDate,
    val now: Instant,
    val zone: TimeZone,
    val onLaneClick: (String) -> Unit,
)

@Composable
internal fun TimelineView(params: TimelineViewParams) {
    val defs = params.defs
    val weekStart = params.weekStart
    val today = params.today
    val now = params.now
    val zone = params.zone
    val onLaneClick = params.onLaneClick
    val timeline = remember(defs, weekStart, now) {
        ScheduleProjection.timeline(defs, now, zone, startDate = weekStart, days = 7)
    }
    val days = remember(weekStart) { (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) } }
    val nowFrac = remember(now, zone) { now.toLocalDateTime(zone).let { (it.hour * 60 + it.minute) / 1440f } }
    val laneLabelWidth = 200.dp
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp)) {
        // Day header (today gets a "now" pill).
        Row(Modifier.fillMaxWidth().padding(start = laneLabelWidth, bottom = 8.dp)) {
            days.forEach { date ->
                val isToday = date == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${ScheduleFormat.weekdayShort(date.dayOfWeek)} ${date.day}", style = MaterialTheme.typography.labelSmall, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isToday) {
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 1.dp)) {
                            Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items = timeline.lanes, key = { it.scheduleId }) { lane ->
                val highFreq = lane.ticks.size > 7 * WEEK_GRID_MAX_PER_DAY
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clickable { onLaneClick(lane.scheduleId) }
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(laneLabelWidth).padding(end = 12.dp)) {
                        Text(lane.scheduleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lane.cadence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    days.forEach { date ->
                        TimelineDayCell(
                            params = TimelineDayCellParams(
                                date = date,
                                today = today,
                                highFreq = highFreq,
                                nowFrac = nowFrac,
                                ticks = lane.ticks.filter { it.instant.toLocalDateTime(zone).date == date },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        TimelineLegend()
    }
}

internal data class TimelineDayCellParams(
    val date: LocalDate,
    val today: LocalDate,
    val highFreq: Boolean,
    val nowFrac: Float,
    val ticks: List<com.letta.mobile.data.schedules.TimelineTick>,
)

@Composable
internal fun TimelineDayCell(
    params: TimelineDayCellParams,
    modifier: Modifier = Modifier,
) {
    val date = params.date
    val today = params.today
    val highFreq = params.highFreq
    val nowFrac = params.nowFrac
    val ticks = params.ticks
    val success = MaterialTheme.customColors.successColor
    val upcoming = MaterialTheme.colorScheme.outline
    val isToday = date == today
    Box(
        modifier.fillMaxHeight().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        if (highFreq && ticks.isNotEmpty()) {
            // Hourly+ schedules render as a continuous bar (filled = past). Only
            // paint days that actually have ticks, so a day-restricted
            // high-frequency cron doesn't show density on its off days.
            Row(Modifier.fillMaxWidth(0.86f).height(14.dp).clip(RoundedCornerShape(3.dp))) {
                when {
                    date < today -> Box(Modifier.fillMaxSize().background(success))
                    date > today -> Box(Modifier.fillMaxSize().border(1.dp, upcoming, RoundedCornerShape(3.dp)))
                    else -> {
                        val frac = nowFrac.coerceIn(0.02f, 0.98f)
                        Box(Modifier.weight(frac).fillMaxHeight().background(success))
                        Box(Modifier.weight(1f - frac).fillMaxHeight().border(1.dp, upcoming))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ticks.take(6).forEach { tick ->
                    val past = tick.status == RunStatus.Done || tick.status == RunStatus.Failed
                    Box(
                        Modifier.size(width = 6.dp, height = 18.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (past) statusColor(tick.status) else Color.Transparent)
                            .border(1.dp, statusColor(tick.status).copy(alpha = if (past) 1f else 0.6f), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        // Vertical now-line through today's cell.
        if (isToday) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(nowFrac.coerceIn(0.01f, 0.99f)))
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.weight(1f - nowFrac.coerceIn(0.01f, 0.99f)))
            }
        }
    }
}

@Composable
internal fun TimelineLegend() {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(MaterialTheme.customColors.successColor, "success", filled = true)
        LegendItem(MaterialTheme.colorScheme.error, "failed", filled = true)
        LegendItem(MaterialTheme.colorScheme.outline, "upcoming", filled = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 2.dp, height = 14.dp).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(5.dp))
            Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LegendItem(color: Color, label: String, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (filled) color else Color.Transparent).border(1.dp, color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- History ----------------------------------------------------------------

@Composable
internal fun HistoryView(summary: com.letta.mobile.data.schedules.HistorySummary) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { StatsCard(summary) }
        item { Text("RELIABILITY · LAST 12 RUNS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor) }
        items(items = summary.schedules, key = { it.scheduleId }) { rel ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rel.scheduleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    val ok = rel.failed == 0
                    Text("${rel.succeeded}/${rel.total}", style = MaterialTheme.typography.labelMedium, color = if (ok) MaterialTheme.customColors.successColor else MaterialTheme.customColors.runningColor)
                }
                Spacer(Modifier.height(6.dp))
                ReliabilityStrip(rel.squares)
            }
        }
        if (summary.totalRuns == 0) {
            item {
                Text("No run history recorded yet — schedules will populate this as they fire.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
            }
        }
    }
}

@Composable
internal fun StatsCard(summary: com.letta.mobile.data.schedules.HistorySummary) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 18.dp),
    ) {
        StatCell("${summary.totalRuns}", "runs · 30d", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        StatCell(summary.overallSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "—", "success", MaterialTheme.customColors.successColor, Modifier.weight(1f))
        StatCell("—", "avg run", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
internal fun StatCell(value: String, label: String, valueColor: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 18.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

@Composable
internal fun ReliabilityStrip(squares: List<Boolean?>, count: Int = 12) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val padded = (squares.takeLast(count) + List(count) { null }).take(count)
        padded.forEach { ok ->
            val color = when (ok) {
                true -> MaterialTheme.customColors.successColor
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

