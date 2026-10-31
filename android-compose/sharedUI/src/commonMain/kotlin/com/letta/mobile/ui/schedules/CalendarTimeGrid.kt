package com.letta.mobile.ui.schedules

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** One day column of a calendar grid. */
@Immutable
data class CalendarColumn(val date: LocalDate, val isToday: Boolean)

/** Where a [CalendarTimeGrid] child sits: a time of day, in lane [lane] of [lanes] side-by-side lanes. */
@Immutable
data class CalendarSlot(val minuteOfDay: Int, val lane: Int = 0, val lanes: Int = 1)

/** Places this child of a [CalendarTimeGrid] column at [slot]. */
fun Modifier.calendarSlot(slot: CalendarSlot): Modifier = layoutId(slot)

/** What a [CalendarTimeGrid] draws and reports. */
@Immutable
class CalendarTimeGridSpec(
    val columns: List<CalendarColumn>,
    /** The gutter's label for an hour (0..23); hour 0 is left blank, as it sits under the header. */
    val hourLabel: (Int) -> String,
    /** Now, in minutes since midnight, drawn as a line across today's column; null hides it. */
    val nowMinute: Int?,
    /** Where the grid opens scrolled to, in minutes since midnight. */
    val initialMinute: Int,
    /** A tap on an empty part of a column: its date and the hour tapped. Null leaves the grid inert. */
    val onEmptySlot: ((LocalDate, Int) -> Unit)? = null,
)

/**
 * A scrolling day-by-hour grid: an hour gutter, one column per [CalendarTimeGridSpec.columns], hour
 * rules, today's column tinted with a now line across it. [columnContent] draws a column's blocks;
 * each places itself with [calendarSlot]. Static: nothing animates or redraws per frame.
 */
@Composable
fun CalendarTimeGrid(
    spec: CalendarTimeGridSpec,
    modifier: Modifier = Modifier,
    columnContent: @Composable (index: Int) -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        scroll.scrollTo(with(density) { (CalendarDimens.hourHeight * (spec.initialMinute / MINUTES_PER_HOUR)).roundToPx() })
    }
    val rule = MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)
    Column(modifier.fillMaxSize().verticalScroll(scroll)) {
        Row(Modifier.fillMaxWidth().height(CalendarDimens.hourHeight * HOURS_PER_DAY)) {
            HourGutter(spec.hourLabel)
            Row(Modifier.weight(1f).fillMaxHeight().hourRules(rule)) {
                spec.columns.forEachIndexed { index, column ->
                    GridColumn(column, spec, rule, Modifier.weight(1f).fillMaxHeight()) { columnContent(index) }
                }
            }
        }
    }
}

@Composable
private fun HourGutter(hourLabel: (Int) -> String) {
    Column(Modifier.width(CalendarDimens.gutterWidth)) {
        repeat(HOURS_PER_DAY) { hour ->
            Box(Modifier.fillMaxWidth().height(CalendarDimens.hourHeight), contentAlignment = Alignment.TopEnd) {
                if (hour > 0) {
                    Text(
                        hourLabel(hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.customColors.onSurfaceMutedColor,
                        modifier = Modifier.offset(y = -LettaDimens.Space.sm).padding(end = LettaDimens.Space.sm),
                    )
                }
            }
        }
    }
}

private fun Modifier.hourRules(color: Color): Modifier = drawBehind {
    val hour = size.height / HOURS_PER_DAY
    for (h in 1 until HOURS_PER_DAY) {
        drawLine(color, Offset(0f, hour * h), Offset(size.width, hour * h))
    }
}

@Composable
private fun GridColumn(
    column: CalendarColumn,
    spec: CalendarTimeGridSpec,
    rule: Color,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val tint = if (column.isToday) MaterialTheme.colorScheme.primary.copy(alpha = TODAY_TINT_ALPHA) else Color.Transparent
    val onEmptySlot by rememberUpdatedState(spec.onEmptySlot)
    val hourPx = with(LocalDensity.current) { CalendarDimens.hourHeight.toPx() }
    val tap = if (spec.onEmptySlot == null) {
        Modifier
    } else {
        Modifier.pointerInput(column.date) {
            detectTapGestures { offset -> onEmptySlot?.invoke(column.date, (offset.y / hourPx).toInt().coerceIn(0, HOURS_PER_DAY - 1)) }
        }
    }
    Box(
        modifier
            .drawBehind {
                drawRect(tint)
                drawLine(rule, Offset(0f, 0f), Offset(0f, size.height))
            }
            .then(tap),
    ) {
        SlotLayout(content)
        val now = spec.nowMinute
        if (column.isToday && now != null) CalendarNowLine(now)
    }
}

/** Measures every child to one lane's width and the event height, and places it at its [CalendarSlot]. */
@Composable
private fun SlotLayout(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val hourPx = with(density) { CalendarDimens.hourHeight.toPx() }
    val eventPx = with(density) { CalendarDimens.eventHeight.roundToPx() }
    val gapPx = with(density) { LettaDimens.Space.hair.roundToPx() }
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val width = constraints.maxWidth
        val placed = measurables.map { measurable ->
            val slot = measurable.layoutId as? CalendarSlot ?: CalendarSlot(0)
            val laneWidth = (width - gapPx) / slot.lanes.coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed((laneWidth - gapPx).coerceAtLeast(0), eventPx))
            Triple(placeable, gapPx + slot.lane * laneWidth, (hourPx * slot.minuteOfDay / MINUTES_PER_HOUR).roundToInt())
        }
        layout(width, constraints.maxHeight) {
            placed.forEach { (placeable, x, y) -> placeable.place(x, y) }
        }
    }
}

/** The now line across a day column: a dot at the column's start and a rule, at [minuteOfDay]. */
@Composable
fun CalendarNowLine(minuteOfDay: Int, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .offset(y = CalendarDimens.hourHeight * (minuteOfDay / MINUTES_PER_HOUR) - CalendarDimens.nowDot / 2)
            .height(CalendarDimens.nowDot)
            .drawBehind {
                val y = size.height / 2
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = LettaDimens.Stroke.hairline.toPx() * 2)
                drawCircle(color, radius = size.height / 2, center = Offset(size.height / 2, y))
            },
    )
}

internal const val HOURS_PER_DAY = 24
internal const val MINUTES_PER_HOUR = 60f
private const val TODAY_TINT_ALPHA = 0.05f
