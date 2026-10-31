package com.letta.mobile.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/** One cell of a [CalendarMonthGrid]. [inMonth] false dims a leading or trailing day of a neighbour month. */
@Immutable
data class CalendarMonthDay(
    val column: CalendarColumn,
    val inMonth: Boolean,
    val selected: Boolean,
    val description: String,
)

/** What a [CalendarMonthGrid] draws: whole weeks of [days], [weekdays] over them, cells [cellHeight] tall. */
@Immutable
class CalendarMonthSpec(
    val days: List<CalendarMonthDay>,
    val weekdays: List<String>,
    val cellHeight: Dp,
)

/**
 * A month as whole weeks: weekday initials, then a row of seven cells per week, each with its date
 * and [cellContent] (event lines on a wide page, dots on a phone). Tapping a cell selects its day.
 */
@Composable
fun CalendarMonthGrid(
    spec: CalendarMonthSpec,
    onSelect: (CalendarMonthDay) -> Unit,
    modifier: Modifier = Modifier,
    cellContent: @Composable ColumnScope.(CalendarMonthDay) -> Unit,
) {
    val rule = MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = LettaDimens.Space.xs)) {
            spec.weekdays.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.customColors.onSurfaceMutedColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        spec.days.chunked(spec.weekdays.size.coerceAtLeast(1)).forEach { week ->
            Row(Modifier.fillMaxWidth().height(spec.cellHeight)) {
                week.forEach { day -> MonthCell(day, onSelect, rule, Modifier.weight(1f), cellContent) }
            }
        }
    }
}

@Composable
private fun MonthCell(
    day: CalendarMonthDay,
    onSelect: (CalendarMonthDay) -> Unit,
    rule: Color,
    modifier: Modifier,
    content: @Composable ColumnScope.(CalendarMonthDay) -> Unit,
) {
    val selectedWash = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier
            .drawBehind { drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f)) }
            .then(if (day.selected) Modifier.background(selectedWash, MaterialTheme.shapes.small) else Modifier)
            .clickable { onSelect(day) }
            .semantics {
                contentDescription = day.description
                selected = day.selected
                role = Role.Button
            }
            .padding(LettaDimens.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        CalendarDateBadge(day.column.date.day, isToday = day.column.isToday, selected = false, dim = !day.inMonth)
        content(day)
    }
}
