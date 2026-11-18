package com.letta.mobile.ui.chat.surface.timeline.days

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

// The timeline's calendar: what day a row's timestamp falls on, what its divider says, and the
// reader's current date. It reads no timeline types, so the lists depend on it and not the reverse.

/**
 * The local dates of a list's timestamps in [zone], each parsed once. One per list, remembered with
 * it: the list's rows are rebuilt on every message emission, and parsing (with its zone conversion)
 * every row's timestamp again each time was the heaviest part of an append.
 */
internal class TimelineDays(private val zone: TimeZone = TimeZone.currentSystemDefault()) {
    private val parsed = HashMap<String, LocalDate?>()

    fun of(timestamp: String): LocalDate? {
        if (timestamp in parsed) return parsed[timestamp]
        // Bounded: a list that outlives this many distinct stamps starts over rather than grow.
        if (parsed.size >= MAX_KEPT_DAYS) parsed.clear()
        return parseTimelineLocalDate(timestamp, zone).also { parsed[timestamp] = it }
    }

    private companion object {
        const val MAX_KEPT_DAYS = 8_192
    }
}

/** ISO instant (`Z` or offset) in [zone], else a zone-less local date-time, else null. */
internal fun parseTimelineLocalDate(timestamp: String, zone: TimeZone): LocalDate? {
    if (timestamp.isBlank()) return null
    return runCatching { Instant.parse(timestamp).toLocalDateTime(zone).date }
        .recoverCatching { LocalDateTime.parse(timestamp).date }
        .getOrNull()
}

/** What a divider says, relative to [today]. Resolved to text by the composable. */
internal sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel
    data class Date(val text: String) : DayLabel
}

private val SameYearFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_FULL)
    char(' ')
    day(padding = kotlinx.datetime.format.Padding.NONE)
}

private val OtherYearFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_FULL)
    char(' ')
    day(padding = kotlinx.datetime.format.Padding.NONE)
    chars(", ")
    year()
}

/** "Today" / "Yesterday" / "March 4" / "December 30, 2025" (desktop and Android copy). */
internal fun dayLabelOf(date: LocalDate, today: LocalDate): DayLabel = when {
    date.daysUntil(today) == 0 -> DayLabel.Today
    date.daysUntil(today) == 1 -> DayLabel.Yesterday
    date.year == today.year -> DayLabel.Date(SameYearFormat.format(date))
    else -> DayLabel.Date(OtherYearFormat.format(date))
}

/** Milliseconds from [now] to the next local midnight in [zone]; floored at 1 so a wait loop yields. */
internal fun millisUntilNextMidnight(now: Instant, zone: TimeZone): Long {
    val nextMidnight = now.toLocalDateTime(zone).date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
    return (nextMidnight - now).inWholeMilliseconds.coerceAtLeast(1L)
}

/**
 * The current local date, recomputed when the day turns over, so "Today" does not keep naming
 * yesterday in a window left open overnight. One watcher per list, not per divider.
 */
@Composable
internal fun rememberCurrentDate(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate {
    var today by remember(zone) { mutableStateOf(Clock.System.todayIn(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            delay(millisUntilNextMidnight(Clock.System.now(), zone).milliseconds)
            today = Clock.System.todayIn(zone)
        }
    }
    return today
}
