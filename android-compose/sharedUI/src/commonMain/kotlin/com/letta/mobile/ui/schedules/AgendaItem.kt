package com.letta.mobile.ui.schedules

import com.letta.mobile.data.schedules.CalendarDay
import com.letta.mobile.data.schedules.CalendarEntry
import kotlinx.datetime.LocalDate

/** One line of the agenda: a day heading, an empty day, a run or band, or the now line. */
internal sealed interface AgendaItem {
    val key: String

    data class Heading(val date: LocalDate, val count: Int) : AgendaItem {
        override val key: String get() = "h:$date"
    }

    data class Empty(val date: LocalDate) : AgendaItem {
        override val key: String get() = "e:$date"
    }

    data class Entry(val date: LocalDate, val entry: CalendarEntry) : AgendaItem {
        override val key: String
            get() = when (entry) {
                is CalendarEntry.Event -> "r:${entry.event.run.scheduleId}:${entry.event.run.instant}"
                is CalendarEntry.Band -> "b:$date:${entry.band.scheduleId}"
            }
    }

    data class Now(val minuteOfDay: Int) : AgendaItem {
        override val key: String get() = "now"
    }
}

/**
 * The agenda's lines for [days]: each day's heading then its entries in time order, a now line on
 * [today] between what has happened and what is coming, and an empty note for a day with nothing.
 */
internal fun agendaItems(days: List<CalendarDay>, today: LocalDate, nowMinute: Int): List<AgendaItem> = buildList {
    days.forEach { day ->
        add(AgendaItem.Heading(day.date, day.runCount))
        val entries = day.entries
        if (entries.isEmpty()) add(AgendaItem.Empty(day.date))
        var nowPlaced = day.date != today
        entries.forEach { entry ->
            if (!nowPlaced && entry.minuteOfDay > nowMinute) {
                add(AgendaItem.Now(nowMinute))
                nowPlaced = true
            }
            add(AgendaItem.Entry(day.date, entry))
        }
        if (!nowPlaced && entries.isNotEmpty()) add(AgendaItem.Now(nowMinute))
    }
}
