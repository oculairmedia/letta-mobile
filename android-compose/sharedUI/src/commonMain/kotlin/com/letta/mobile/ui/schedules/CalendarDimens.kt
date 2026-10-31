package com.letta.mobile.ui.schedules

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The calendar components' geometry (the Calendar* components in ui/components): the time grid, its event blocks, the
 * week strip and the month grid. Spacing, radii and type still come from [LettaDimens] and
 * MaterialTheme; these are only the sizes a calendar needs that the shared scale does not name.
 */
object CalendarDimens {
    /** One hour of a time grid. A 30-minute event block is half of it. */
    val hourHeight: Dp = 48.dp

    /** The time grid's hour-label gutter. */
    val gutterWidth: Dp = 56.dp

    /** An event block or a collapsed band on a time grid: one line of label text. */
    val eventHeight: Dp = 22.dp

    /** The accent stripe down an event block's leading edge. */
    val eventStripe: Dp = 3.dp

    /** The dot on the now line, and the agent dots under a day. */
    val nowDot: Dp = 8.dp
    val dot: Dp = 6.dp

    /** A month cell on a pointer-width grid (room for three entries) and on a phone (dots only). */
    val monthCellHeight: Dp = 128.dp
    val monthCellCompactHeight: Dp = 52.dp

    /** An agenda row's time column. */
    val agendaTimeWidth: Dp = 76.dp

    /** Narrowest content that still shows a seven-column week grid; below it the week is one day at a time. */
    val weekGridMinWidth: Dp = 600.dp

    /** Narrowest page that docks a detail rail beside a calendar; below it the detail opens as a sheet. */
    val railDockBreakpoint: Dp = 1000.dp
}
