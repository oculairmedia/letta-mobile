package com.letta.mobile.ui.schedules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.schedules.CalendarDay
import com.letta.mobile.data.schedules.CalendarEntry
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.LocalDate

/**
 * The month: whole weeks, each day with what it holds. With room (a pointer-width page) a cell
 * lists its first runs and "+N more", and opening a day goes to its week; on a phone a cell shows
 * one dot per agent and the selected day's agenda follows under the grid.
 */
@Composable
fun ScheduleMonthCalendar(params: ScheduleCalendarParams, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= CalendarDimens.weekGridMinWidth) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.lg)) {
                MonthGrid(params, CalendarDimens.monthCellHeight, onSelect = params.actions.onOpenDay) { day -> WideCell(day, params) }
            }
        } else {
            val selected = params.days.filter { it.date == params.selected }
            val dayParams = ScheduleCalendarParams(
                days = selected,
                today = params.today,
                selected = params.selected,
                clock = params.clock,
                cadenceById = params.cadenceById,
                actions = params.actions,
            )
            ScheduleAgendaList(dayParams) {
                item(key = "month-grid") {
                    MonthGrid(params, CalendarDimens.monthCellCompactHeight, onSelect = params.actions.onSelectDate) { day ->
                        CalendarDots(day.agentIds.map { scheduleAccentOfRaw(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    params: ScheduleCalendarParams,
    cellHeight: Dp,
    onSelect: (LocalDate) -> Unit,
    cell: @Composable (CalendarDay) -> Unit,
) {
    val byDate = params.days.associateBy { it.date }
    val month = params.selected.month
    val days = params.days.map { day ->
        CalendarMonthDay(
            column = CalendarColumn(day.date, day.date == params.today),
            inMonth = day.date.month == month,
            selected = day.date == params.selected,
            description = "${ScheduleRunText.longDate(day.date)}, ${ScheduleRunText.runCount(day.runCount)}",
        )
    }
    val weekdays = (1..DAYS_PER_WEEK).map { ScheduleFormat.weekdayShort(it) }
    CalendarMonthGrid(CalendarMonthSpec(days, weekdays, cellHeight), onSelect = { onSelect(it.column.date) }) { monthDay ->
        byDate[monthDay.column.date]?.let { cell(it) }
    }
}

@Composable
private fun WideCell(day: CalendarDay, params: ScheduleCalendarParams) {
    val entries = day.entries
    entries.take(WIDE_CELL_ENTRIES).forEach { entry ->
        val spec = when (entry) {
            is CalendarEntry.Event -> runEventSpec(entry.event.run, params.clock.zone, params.clock.now)
            is CalendarEntry.Band -> bandEventSpec(entry.band, params.cadenceById[entry.band.scheduleId])
        }
        CalendarEventBlock(
            spec = spec,
            onClick = {
                when (entry) {
                    is CalendarEntry.Event -> params.actions.onRunClick(entry.event.run)
                    is CalendarEntry.Band -> params.actions.onScheduleClick(entry.band.scheduleId)
                }
            },
            modifier = Modifier.fillMaxWidth().height(CalendarDimens.eventHeight),
        )
    }
    val more = entries.size - WIDE_CELL_ENTRIES
    if (more > 0) {
        Text("+$more more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

private const val WIDE_CELL_ENTRIES = 3
private const val DAYS_PER_WEEK = 7
