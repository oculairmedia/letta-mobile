package com.letta.mobile.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.schedules.CalendarBand
import com.letta.mobile.data.schedules.CalendarEntry
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.datetime.toLocalDateTime

/**
 * The agenda: day by day ("Today", "Tomorrow", then dates), every run with its time, colour,
 * recurrence and outcome, high-frequency schedules as one row per day. Opens at the now line.
 * [header] draws above the days (the phone's month grid).
 */
@Composable
fun ScheduleAgendaList(params: ScheduleCalendarParams, modifier: Modifier = Modifier, header: (LazyListScope.() -> Unit)? = null) {
    val zone = params.clock.zone
    val local = params.clock.now.toLocalDateTime(zone)
    val nowMinute = local.hour * MINUTES + local.minute
    val items = remember(params.days, params.today, nowMinute) { agendaItems(params.days, params.today, nowMinute) }
    val nowIndex = items.indexOfFirst { it is AgendaItem.Now }
    val initial = if (header == null) (nowIndex - NOW_LEAD_ROWS).coerceAtLeast(0) else 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(LettaDimens.Space.lg)) {
        header?.invoke(this)
        items(items, key = { it.key }) { item -> AgendaLine(item, params) }
    }
}

@Composable
private fun AgendaLine(item: AgendaItem, params: ScheduleCalendarParams) {
    when (item) {
        is AgendaItem.Heading -> LettaSectionLabel(
            "${ScheduleRunText.dayHeading(item.date, params.today)} · ${ScheduleRunText.runCount(item.count)}",
            Modifier.semantics { heading() },
        )
        is AgendaItem.Empty -> LettaEmptyHint("Nothing scheduled", Modifier.padding(bottom = LettaDimens.Space.sm))
        is AgendaItem.Now -> CalendarNowDivider("Now · ${ScheduleRunText.clock(item.minuteOfDay)}")
        is AgendaItem.Entry -> when (val entry = item.entry) {
            is CalendarEntry.Event -> RunRow(entry, params)
            is CalendarEntry.Band -> BandRow(entry.band, params)
        }
    }
}

@Composable
private fun RunRow(entry: CalendarEntry.Event, params: ScheduleCalendarParams) {
    val run = entry.event.run
    LettaListRow(
        spec = LettaListRowSpec(
            title = run.scheduleName,
            subtitle = params.cadenceById[run.scheduleId],
            trailing = ScheduleRunText.trailing(run, params.clock.now),
            emphasized = run.status == RunStatus.Next,
            quiet = run.status.isPast || run.status == RunStatus.Paused,
        ),
        onClick = { params.actions.onRunClick(run) },
        modifier = Modifier.heightIn(min = LettaDimens.Control.minTouchTarget),
        leading = { AgendaLeading(ScheduleFormat.timeOfDay(run.instant, params.clock.zone), scheduleAccentOfRaw(run.agentId)) },
        end = outcomeBadge(run.status)?.let { badge -> { BadgeIcon(badge) } },
    )
}

@Composable
private fun BandRow(band: CalendarBand, params: ScheduleCalendarParams) {
    val cadence = params.cadenceById[band.scheduleId]
    val failed = band.count(RunStatus.Failed)
    val parts = listOfNotNull(cadence, ScheduleRunText.runCount(band.count), "$failed failed".takeIf { failed > 0 })
    val subtitle = parts.joinToString(" · ")
    val next = band.next
    LettaListRow(
        spec = LettaListRowSpec(
            title = band.scheduleName,
            subtitle = subtitle,
            trailing = next?.let { "next ${ScheduleFormat.timeOfDay(it.instant, params.clock.zone)}" } ?: "Done for the day",
            quiet = next == null || band.paused,
        ),
        onClick = { params.actions.onScheduleClick(band.scheduleId) },
        modifier = Modifier.heightIn(min = LettaDimens.Control.minTouchTarget),
        leading = {
            val allDay = band.firstMinute < MINUTES && band.lastMinute >= LAST_HOUR_MINUTE
            val window = if (allDay) "All day" else ScheduleRunText.window(band.firstMinute, band.lastMinute)
            AgendaLeading(window, scheduleAccentOfRaw(band.agentId))
        },
    )
}

/** The time column and the agent's colour stripe at the start of an agenda row. */
@Composable
private fun AgendaLeading(time: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Text(
            time,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(CalendarDimens.agendaTimeWidth),
        )
        Box(Modifier.width(CalendarDimens.eventStripe * 2).height(LettaDimens.Orb.md).clip(MaterialTheme.shapes.small).background(accent))
    }
}

@Composable
private fun BadgeIcon(badge: CalendarBadge) {
    Icon(badge.icon, contentDescription = badge.description, tint = badge.tint, modifier = Modifier.size(LettaDimens.Control.icon))
}

private const val MINUTES = 60
private const val NOW_LEAD_ROWS = 3
private const val LAST_HOUR_MINUTE = 23 * MINUTES
