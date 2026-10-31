package com.letta.mobile.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/**
 * A day's number in a circle: filled with the primary colour when [selected], primary text when it
 * is today. The day headers, the week strip and the month grid all draw their date with it.
 */
@Composable
fun CalendarDateBadge(number: Int, isToday: Boolean, selected: Boolean, modifier: Modifier = Modifier, dim: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val fill = when {
        selected -> scheme.primary
        isToday -> scheme.primary.copy(alpha = TODAY_BADGE_ALPHA)
        else -> Color.Transparent
    }
    val text = when {
        selected -> scheme.onPrimary
        isToday -> scheme.primary
        dim -> MaterialTheme.customColors.onSurfaceMutedColor
        else -> scheme.onSurface
    }
    Box(modifier.size(LettaDimens.Orb.sm).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Text("$number", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = text)
    }
}

/** Up to [max] coloured dots under a day: one per agent with runs that day. */
@Composable
fun CalendarDots(colors: List<Color>, modifier: Modifier = Modifier, max: Int = DEFAULT_DOTS) {
    Row(modifier.height(CalendarDimens.dot), horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        colors.take(max).forEach { color -> Box(Modifier.size(CalendarDimens.dot).clip(CircleShape).background(color)) }
    }
}

/** A day column's heading over a time grid: the weekday, then its date. */
@Composable
fun CalendarDayHeader(weekday: String, column: CalendarColumn, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = LettaDimens.Space.xs), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            weekday,
            style = MaterialTheme.typography.labelSmall,
            color = if (column.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.customColors.onSurfaceMutedColor,
        )
        CalendarDateBadge(column.date.day, isToday = column.isToday, selected = false)
    }
}

/**
 * A row aligned with a [CalendarTimeGrid]'s columns: a [gutterLabel] in the gutter, then [cell] per
 * column. Day headers and the all-day lane of collapsed bands use it.
 */
@Composable
fun CalendarColumnsRow(
    columnCount: Int,
    modifier: Modifier = Modifier,
    gutterLabel: String? = null,
    cell: @Composable ColumnScope.(index: Int) -> Unit,
) {
    Row(modifier.fillMaxWidth()) {
        Box(Modifier.width(CalendarDimens.gutterWidth).padding(end = LettaDimens.Space.sm), contentAlignment = Alignment.TopEnd) {
            gutterLabel?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
            }
        }
        repeat(columnCount) { index ->
            Column(
                Modifier.weight(1f).padding(horizontal = LettaDimens.Space.hair),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { cell(index) }
        }
    }
}

/** One day of a [CalendarWeekStrip]. [description] is read for the whole cell ("Wednesday, October 28, 3 runs"). */
data class CalendarStripDay(
    val column: CalendarColumn,
    val weekday: String,
    val selected: Boolean,
    val dots: List<Color>,
    val description: String,
)

/**
 * A phone's week at a glance: seven finger-sized days, each with dots for what it holds; tapping one
 * selects it. Used above a one-day time grid where seven grid columns would not fit.
 */
@Composable
fun CalendarWeekStrip(days: List<CalendarStripDay>, onSelect: (CalendarStripDay) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        days.forEach { day ->
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = LettaDimens.Control.minTouchTarget)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onSelect(day) }
                    .clearAndSetSemantics {
                        contentDescription = day.description
                        selected = day.selected
                        role = Role.Tab
                    }
                    .padding(vertical = LettaDimens.Space.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
            ) {
                Text(day.weekday, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
                CalendarDateBadge(day.column.date.day, isToday = day.column.isToday, selected = day.selected)
                CalendarDots(day.dots)
            }
        }
    }
}

/** The line an agenda draws at the present moment, labelled with the time. */
@Composable
fun CalendarNowDivider(label: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(CalendarDimens.nowDot).clip(CircleShape).background(color))
        Box(Modifier.weight(1f).height(LettaDimens.Stroke.hairline).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = LettaDimens.Space.sm))
    }
}

private const val DEFAULT_DOTS = 3
private const val TODAY_BADGE_ALPHA = 0.16f
