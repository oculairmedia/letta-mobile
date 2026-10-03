package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.chat.projection.ChatRenderItem
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

/**
 * letta-mobile-bglj6.1: day sections, lifted from desktop's DesktopChatDayDividers and rewritten on
 * kotlinx-datetime so it runs in commonMain.
 *
 * Inserts a [TimelineRow.DayDivider] where the LOCAL day changes, ahead of the new day's first
 * row, as the Android timeline does: the oldest day opens with no divider, so a conversation that
 * fits in one day shows none. Rows whose timestamp is blank or unparseable emit no divider and
 * stay in the open section, so a malformed timestamp cannot tear a turn in half. Input and output
 * are in chat order (oldest first).
 */
internal fun withDayDividers(
    chatOrderRows: List<TimelineRow>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<TimelineRow> {
    if (chatOrderRows.isEmpty()) return chatOrderRows
    val out = ArrayList<TimelineRow>(chatOrderRows.size + 1)
    var currentDay: LocalDate? = null
    chatOrderRows.forEach { row ->
        val day = row.timestampOrNull()?.let { parseTimelineLocalDate(it, zone) }
        if (day != null && day != currentDay) {
            if (currentDay != null) out += TimelineRow.DayDivider(day)
            currentDay = day
        }
        out += row
    }
    return out
}

/**
 * The date a paged row's divider names, or null when its older neighbour shares its day. Emitted
 * with the NEWER row so the paged route never has to materialize a day-grouped list. The oldest
 * resident row ([older] null) opens no divider, as the oldest day does in [withDayDividers]; it
 * gains one when older history that ends on another day loads beneath it. Lifted from desktop's
 * canonicalBoundaryDate, compared in the reader's [zone].
 */
internal fun pagedBoundaryDate(
    newer: ChatRenderItem,
    older: ChatRenderItem?,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): LocalDate? {
    if (older == null) return null
    val newerDay = parseTimelineLocalDate(newer.boundaryTimestamp, zone) ?: return null
    val olderDay = parseTimelineLocalDate(older.boundaryTimestamp, zone)
    return newerDay.takeIf { it != olderDay }
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
