package com.letta.mobile.desktop.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.data.schedules.WeekGrid
import com.letta.mobile.data.schedules.WeekRun
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal data class WeekViewParams(
    val defs: List<ScheduleDef>,
    val weekStart: LocalDate,
    val today: LocalDate,
    val now: Instant,
    val zone: TimeZone,
    val selectedId: String?,
    val onRunClick: (ScheduleRun) -> Unit,
)

private data class WeekGridModel(
    val grid: WeekGrid,
    val visibleRuns: List<WeekRun>,
    val hiddenCount: Int,
    val nowMinutes: Int,
    val todayInWeek: Boolean,
    val gutter: Dp = 56.dp,
)

private data class WeekDayColumnParams(
    val date: LocalDate,
    val today: LocalDate,
    val dayIndex: Int,
    val runs: List<WeekRun>,
    val selectedId: String?,
    val onRunClick: (ScheduleRun) -> Unit,
)

internal data class WeekRunBarParams(
    val run: ScheduleRun,
    val minuteOfDay: Int,
    val emphasized: Boolean,
    val onClick: () -> Unit,
)

@Composable
internal fun WeekView(params: WeekViewParams) {
    val model = rememberWeekGridModel(params)
    Column(Modifier.fillMaxSize()) {
        WeekDayHeaderRow(days = model.grid.days, today = params.today, gutter = model.gutter)
        if (model.visibleRuns.isEmpty() && model.hiddenCount > 0) {
            WeekHighFreqBanner(hiddenCount = model.hiddenCount)
        }
        WeekScrollableGrid(model = model, params = params)
    }
}

@Composable
private fun rememberWeekGridModel(params: WeekViewParams): WeekGridModel {
    val grid = remember(params.defs, params.weekStart, params.now) {
        ScheduleProjection.week(params.defs, params.weekStart, params.now, params.zone)
    }
    val nowMinutes = remember(params.now, params.zone) {
        params.now.toLocalDateTime(params.zone).let { it.hour * 60 + it.minute }
    }
    // Sub-daily schedules would fill every row; exclude them (rail + Timeline show them).
    val frequent = remember(grid) { frequentScheduleIds(grid) }
    val visibleRuns = remember(grid, frequent) {
        grid.runs.filter { it.run.scheduleId !in frequent }
    }
    return WeekGridModel(
        grid = grid,
        visibleRuns = visibleRuns,
        hiddenCount = frequent.size,
        nowMinutes = nowMinutes,
        todayInWeek = params.today in grid.days,
    )
}

private fun frequentScheduleIds(grid: WeekGrid): Set<String> =
    grid.runs
        .groupBy { it.run.scheduleId }
        .filterValues { it.size > 7 * WEEK_GRID_MAX_PER_DAY }
        .keys

@Composable
private fun WeekDayHeaderRow(days: List<LocalDate>, today: LocalDate, gutter: Dp) {
    Row(Modifier.fillMaxWidth().padding(start = gutter, end = 4.dp)) {
        days.forEach { date ->
            WeekDayHeaderCell(date = date, isToday = date == today)
        }
    }
}

@Composable
private fun RowScope.WeekDayHeaderCell(date: LocalDate, isToday: Boolean) {
    Box(
        Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isToday) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ScheduleFormat.weekdayShort(date.dayOfWeek),
                style = MaterialTheme.typography.labelMedium,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${date.day}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeekHighFreqBanner(hiddenCount: Int) {
    val noun = if (hiddenCount == 1) "schedule" else "schedules"
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            "$hiddenCount high-frequency $noun run every hour — see the Timeline view for their run density.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
        )
    }
}

@Composable
private fun WeekScrollableGrid(model: WeekGridModel, params: WeekViewParams) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (HOUR_HEIGHT * 5).roundToPx() }) }
    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT * GRID_HOURS)) {
            WeekHourGutter(gutter = model.gutter)
            model.grid.days.forEachIndexed { dayIndex, date ->
                WeekDayColumn(
                    WeekDayColumnParams(
                        date = date,
                        today = params.today,
                        dayIndex = dayIndex,
                        runs = model.visibleRuns.filter { it.dayIndex == dayIndex },
                        selectedId = params.selectedId,
                        onRunClick = params.onRunClick,
                    ),
                )
            }
        }
        if (model.todayInWeek) {
            WeekNowMarker(gutter = model.gutter, nowMinutes = model.nowMinutes)
        }
    }
}

@Composable
private fun WeekHourGutter(gutter: Dp) {
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
}

@Composable
private fun RowScope.WeekDayColumn(params: WeekDayColumnParams) {
    val isToday = params.date == params.today
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(
                if (isToday) MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f)
                else Color.Transparent,
            ),
    ) {
        WeekHourGridLines()
        Box(
            Modifier.fillMaxHeight().width(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        )
        params.runs.forEach { wr ->
            val emphasized = params.selectedId == null || wr.run.scheduleId == params.selectedId
            WeekRunBar(
                WeekRunBarParams(
                    run = wr.run,
                    minuteOfDay = wr.minuteOfDay,
                    emphasized = emphasized,
                    onClick = { params.onRunClick(wr.run) },
                ),
            )
        }
    }
}

@Composable
private fun WeekHourGridLines() {
    Column(Modifier.fillMaxSize()) {
        (0 until GRID_HOURS).forEach { h ->
            val alpha = if (h % HOUR_LABEL_STEP == 0) 0.4f else 0.18f
            Box(
                Modifier.height(HOUR_HEIGHT).fillMaxWidth().border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha),
                ),
            )
        }
    }
}

@Composable
private fun WeekNowMarker(gutter: Dp, nowMinutes: Int) {
    Row(
        Modifier.fillMaxWidth().offset(y = HOUR_HEIGHT * (nowMinutes / 60f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(gutter), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.size(8.dp).offset(x = 4.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
internal fun WeekRunBar(params: WeekRunBarParams) {
    val color = statusColor(params.run.status)
    val filled = params.run.status == RunStatus.Done || params.run.status == RunStatus.Running
    val fillAlpha = if (params.emphasized) 0.9f else 0.4f
    val borderAlpha = if (params.emphasized) 1f else 0.4f
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp)
            .offset(y = HOUR_HEIGHT * (params.minuteOfDay / 60f))
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (filled) color.copy(alpha = fillAlpha) else Color.Transparent)
            .border(1.dp, color.copy(alpha = borderAlpha), RoundedCornerShape(4.dp))
            .clickable(onClick = params.onClick)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            params.run.scheduleName,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) MaterialTheme.colorScheme.onSurface else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
