package com.letta.mobile.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.schedules.CalendarDay
import com.letta.mobile.data.schedules.CalendarLabels
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/** What a schedule calendar view asks its page to do. */
@Immutable
class ScheduleCalendarActions(
    val onRunClick: (ScheduleRun) -> Unit,
    val onScheduleClick: (String) -> Unit,
    /** Picks a day within the shown range (the phone's week strip, a month cell on a phone). */
    val onSelectDate: (LocalDate) -> Unit,
    /** Opens a day in the week view (a month cell on a wide page). */
    val onOpenDay: (LocalDate) -> Unit,
    /** An empty hour was tapped: start a schedule there. Null when the page cannot create one. */
    val onEmptySlot: ((LocalDate, Int) -> Unit)?,
)

/** A calendar view's input: the projected [days], the day in focus and the recurrence of each schedule. */
@Immutable
class ScheduleCalendarParams(
    val days: List<CalendarDay>,
    val today: LocalDate,
    val selected: LocalDate,
    val clock: ScheduleClock,
    val cadenceById: Map<String, String>,
    val actions: ScheduleCalendarActions,
)

/**
 * The week: seven day columns on a time grid when the page has room
 * ([CalendarDimens.weekGridMinWidth]), else a week strip over the selected day's grid. High-frequency
 * schedules sit in a "Frequent" lane above the grid, one band per day with its run count.
 */
@Composable
fun ScheduleWeekCalendar(params: ScheduleCalendarParams, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= CalendarDimens.weekGridMinWidth) {
            WeekGrid(params, params.days, showHeaders = true)
        } else {
            Column(Modifier.fillMaxSize()) {
                CalendarWeekStrip(
                    days = params.days.map { stripDay(it, params) },
                    onSelect = { params.actions.onSelectDate(it.column.date) },
                    modifier = Modifier.padding(horizontal = LettaDimens.Space.sm),
                )
                val day = params.days.firstOrNull { it.date == params.selected } ?: params.days.first()
                WeekGrid(params, listOf(day), showHeaders = false)
            }
        }
    }
}

@Composable
private fun stripDay(day: CalendarDay, params: ScheduleCalendarParams) = CalendarStripDay(
    column = CalendarColumn(day.date, day.date == params.today),
    weekday = ScheduleFormat.weekdayShort(day.date.dayOfWeek).take(1),
    selected = day.date == params.selected,
    dots = day.agentIds.map { scheduleAccentOfRaw(it) },
    description = "${ScheduleRunText.longDate(day.date)}, ${ScheduleRunText.runCount(day.runCount)}",
)

@Composable
private fun WeekGrid(params: ScheduleCalendarParams, days: List<CalendarDay>, showHeaders: Boolean) {
    val columns = days.map { CalendarColumn(it.date, it.date == params.today) }
    Column(Modifier.fillMaxSize()) {
        if (showHeaders) {
            CalendarColumnsRow(columns.size) { index ->
                CalendarDayHeader(ScheduleFormat.weekdayShort(columns[index].date.dayOfWeek), columns[index])
            }
        }
        if (days.any { it.bands.isNotEmpty() }) FrequentLane(days, params)
        CalendarTimeGrid(gridSpec(params, columns), Modifier.weight(1f)) { index ->
            days[index].events.forEach { event ->
                CalendarEventBlock(
                    spec = runEventSpec(event.run, params.clock.zone, params.clock.now, showTime = event.lanes == 1),
                    onClick = { params.actions.onRunClick(event.run) },
                    modifier = Modifier.calendarSlot(CalendarSlot(event.minuteOfDay, event.lane, event.lanes)),
                )
            }
        }
    }
}

/** The high-frequency schedules' bands, one row per band, above the grid like all-day events. */
@Composable
private fun FrequentLane(days: List<CalendarDay>, params: ScheduleCalendarParams) {
    Column {
        CalendarColumnsRow(days.size, Modifier.padding(vertical = LettaDimens.Space.xs), gutterLabel = "Frequent") { index ->
            days[index].bands.forEach { band ->
                CalendarEventBlock(
                    spec = bandEventSpec(band, params.cadenceById[band.scheduleId]),
                    onClick = { params.actions.onScheduleClick(band.scheduleId) },
                    modifier = Modifier.fillMaxWidth().height(CalendarDimens.eventHeight),
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(LettaDimens.Stroke.hairline)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
        )
    }
}

private fun gridSpec(params: ScheduleCalendarParams, columns: List<CalendarColumn>): CalendarTimeGridSpec {
    val now = params.clock.now.toLocalDateTime(params.clock.zone)
    val nowMinute = now.hour * MINUTES + now.minute
    val showsToday = columns.any { it.isToday }
    val firstEvent = params.days.flatMap { it.events }.minOfOrNull { it.minuteOfDay }
    val initial = when {
        showsToday -> nowMinute - LEAD_MINUTES
        firstEvent != null -> firstEvent - LEAD_MINUTES
        else -> DEFAULT_START_HOUR * MINUTES
    }.coerceIn(0, LAST_START_MINUTE)
    return CalendarTimeGridSpec(
        columns = columns,
        hourLabel = CalendarLabels::hourLabel,
        nowMinute = nowMinute.takeIf { showsToday },
        initialMinute = initial,
        onEmptySlot = params.actions.onEmptySlot,
    )
}

private const val MINUTES = 60
private const val LEAD_MINUTES = 120
private const val DEFAULT_START_HOUR = 8
private const val LAST_START_MINUTE = 16 * MINUTES
