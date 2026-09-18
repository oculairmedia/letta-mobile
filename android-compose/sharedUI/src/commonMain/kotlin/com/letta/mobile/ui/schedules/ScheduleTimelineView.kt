package com.letta.mobile.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.TimelineLane
import com.letta.mobile.data.schedules.TimelineTick
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import com.letta.mobile.ui.theme.LettaDimens

data class TimelineViewParams(
    val defs: List<ScheduleDef>,
    val weekStart: LocalDate,
    val today: LocalDate,
    val now: Instant,
    val zone: TimeZone,
    val onLaneClick: (String) -> Unit,
)

data class TimelineDayCellParams(
    val date: LocalDate,
    val today: LocalDate,
    val highFreq: Boolean,
    val nowFrac: Float,
    val ticks: List<TimelineTick>,
)

private data class TimelineLaneRowParams(
    val lane: TimelineLane,
    val days: List<LocalDate>,
    val today: LocalDate,
    val zone: TimeZone,
    val nowFrac: Float,
    val laneLabelWidth: Dp,
    val onLaneClick: (String) -> Unit,
)

@Composable
fun TimelineView(params: TimelineViewParams) {
    val timeline = remember(params.defs, params.weekStart, params.now) {
        ScheduleProjection.timeline(params.defs, params.now, params.zone, startDate = params.weekStart, days = 7)
    }
    val days = remember(params.weekStart) {
        (0..6).map { params.weekStart.plus(it, DateTimeUnit.DAY) }
    }
    val nowFrac = remember(params.now, params.zone) {
        params.now.toLocalDateTime(params.zone).let { (it.hour * 60 + it.minute) / 1440f }
    }
    val laneLabelWidth = 200.dp
    Column(Modifier.fillMaxSize().padding(start = LettaDimens.Space.xxl, end = LettaDimens.Space.xxl)) {
        TimelineDayHeader(days = days, today = params.today, laneLabelWidth = laneLabelWidth)
        LazyColumn(Modifier.weight(1f)) {
            items(items = timeline.lanes, key = { it.scheduleId }) { lane ->
                TimelineLaneRow(
                    TimelineLaneRowParams(
                        lane = lane,
                        days = days,
                        today = params.today,
                        zone = params.zone,
                        nowFrac = nowFrac,
                        laneLabelWidth = laneLabelWidth,
                        onLaneClick = params.onLaneClick,
                    ),
                )
            }
        }
        TimelineLegend()
    }
}

@Composable
private fun TimelineDayHeader(days: List<LocalDate>, today: LocalDate, laneLabelWidth: Dp) {
    Row(Modifier.fillMaxWidth().padding(start = laneLabelWidth, bottom = LettaDimens.Space.sm)) {
        days.forEach { date ->
            TimelineDayHeaderCell(date = date, isToday = date == today)
        }
    }
}

@Composable
private fun RowScope.TimelineDayHeaderCell(date: LocalDate, isToday: Boolean) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${ScheduleFormat.weekdayShort(date.dayOfWeek)} ${date.day}",
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isToday) {
            Spacer(Modifier.height(LettaDimens.Space.hair))
            Box(
                Modifier.clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = LettaDimens.Space.sm, vertical = 1.dp),
            ) {
                Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun TimelineLaneRow(params: TimelineLaneRowParams) {
    val highFreq = params.lane.ticks.size > 7 * WEEK_GRID_MAX_PER_DAY
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable { params.onLaneClick(params.lane.scheduleId) }
            .border(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(params.laneLabelWidth).padding(end = LettaDimens.Space.md)) {
            Text(
                params.lane.scheduleName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                params.lane.cadence,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.customColors.onSurfaceMutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        params.days.forEach { date ->
            TimelineDayCell(
                params = TimelineDayCellParams(
                    date = date,
                    today = params.today,
                    highFreq = highFreq,
                    nowFrac = params.nowFrac,
                    ticks = params.lane.ticks.filter {
                        it.instant.toLocalDateTime(params.zone).date == date
                    },
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun TimelineDayCell(
    params: TimelineDayCellParams,
    modifier: Modifier = Modifier,
) {
    val isToday = params.date == params.today
    Box(
        modifier.fillMaxHeight().border(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        if (params.highFreq && params.ticks.isNotEmpty()) {
            TimelineHighFreqBar(date = params.date, today = params.today, nowFrac = params.nowFrac)
        } else {
            TimelineTickMarks(ticks = params.ticks)
        }
        if (isToday) {
            TimelineNowLine(nowFrac = params.nowFrac)
        }
    }
}

@Composable
private fun TimelineHighFreqBar(date: LocalDate, today: LocalDate, nowFrac: Float) {
    val success = MaterialTheme.customColors.successColor
    val upcoming = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth(0.86f).height(LettaDimens.Space.lg).clip(RoundedCornerShape(LettaDimens.Radius.sm))) {
        when {
            date < today -> Box(Modifier.fillMaxSize().background(success))
            date > today -> Box(Modifier.fillMaxSize().border(1.dp, upcoming, RoundedCornerShape(LettaDimens.Radius.sm)))
            else -> {
                val frac = nowFrac.coerceIn(0.02f, 0.98f)
                Box(Modifier.weight(frac).fillMaxHeight().background(success))
                Box(Modifier.weight(1f - frac).fillMaxHeight().border(1.dp, upcoming))
            }
        }
    }
}

@Composable
private fun TimelineTickMarks(ticks: List<TimelineTick>) {
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        ticks.take(6).forEach { tick ->
            val past = tick.status == RunStatus.Done || tick.status == RunStatus.Failed
            Box(
                Modifier.size(width = LettaDimens.Space.sm, height = LettaDimens.Control.icon).clip(RoundedCornerShape(LettaDimens.Radius.sm))
                    .background(if (past) statusColor(tick.status) else Color.Transparent)
                    .border(
                        1.dp,
                        statusColor(tick.status).copy(alpha = if (past) 1f else 0.6f),
                        RoundedCornerShape(LettaDimens.Radius.sm),
                    ),
            )
        }
    }
}

@Composable
private fun TimelineNowLine(nowFrac: Float) {
    val clamped = nowFrac.coerceIn(0.01f, 0.99f)
    Row(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(clamped))
        Box(Modifier.width(LettaDimens.Space.hair).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.weight(1f - clamped))
    }
}

@Composable
fun TimelineLegend() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.md),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
    ) {
        LegendItem(MaterialTheme.customColors.successColor, "success", filled = true)
        LegendItem(MaterialTheme.colorScheme.error, "failed", filled = true)
        LegendItem(MaterialTheme.colorScheme.outline, "upcoming", filled = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = LettaDimens.Space.hair, height = LettaDimens.Control.iconSm).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(LettaDimens.Space.xs))
            Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(LettaDimens.Control.iconSm).clip(RoundedCornerShape(LettaDimens.Radius.sm))
                .background(if (filled) color else Color.Transparent)
                .border(1.dp, color, RoundedCornerShape(LettaDimens.Radius.sm)),
        )
        Spacer(Modifier.width(LettaDimens.Space.xs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
