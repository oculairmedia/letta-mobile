package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Splits a conversation's rows into day sections.
 *
 * The list previously carried one hardcoded "Today" header, so a conversation
 * reopened after a few days claimed every message in it was from today, and the
 * per-message clock ("9:41 AM") gave no way to tell otherwise. This inserts a
 * [DesktopChatRow.DayDivider] ahead of the first row of each local day, matching
 * the Android client's date separators.
 *
 * Rows whose timestamp is blank or unparseable emit no divider — they stay
 * attached to the day section already open, which keeps a malformed timestamp
 * from tearing a turn in half.
 */
internal fun withDesktopDayDividers(
    rows: List<DesktopChatRow>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DesktopChatRow> {
    if (rows.isEmpty()) return rows
    val out = ArrayList<DesktopChatRow>(rows.size + 1)
    var currentDay: LocalDate? = null
    rows.forEach { row ->
        val day = row.timestampOrNull()
            ?.let { parseMessageTimestamp(IsoTimestamp(it), zone) }
            ?.toLocalDate()
        if (day != null && day != currentDay) {
            currentDay = day
            out += DesktopChatRow.DayDivider(day)
        }
        out += row
    }
    // A conversation whose every timestamp is unreadable still gets a heading,
    // so the list never loses the top marker the layout was built around.
    if (currentDay == null) out.add(0, DesktopChatRow.DayDivider(LocalDate.now(zone)))
    return out
}

private fun DesktopChatRow.timestampOrNull(): String? = when (this) {
    is DesktopChatRow.Item -> item.boundaryTimestamp
    is DesktopChatRow.ToolGroup -> boundaryTimestamp
    is DesktopChatRow.DayDivider -> null
}

// Built once rather than per label: ofPattern recompiles the pattern each call.
private val SameYearDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d")
private val OtherYearDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

/** "Today" / "Yesterday" / "March 4" / "March 4, 2024" — mirrors the Android separator copy. */
internal fun desktopDayLabel(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        date.year == today.year -> date.format(SameYearDayFormatter)
        else -> date.format(OtherYearDayFormatter)
    }
}

/**
 * The current local date, recomputed when the day actually turns over.
 *
 * The day labels are relative ("Today" / "Yesterday"), so reading the clock
 * once at composition is not enough: a desktop window left open overnight kept
 * calling yesterday's messages today until something unrelated forced a
 * recomposition. This sleeps until the next local midnight and re-reads.
 */
@Composable
internal fun rememberCurrentDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    var today by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            delay(millisUntilNextMidnight(ZonedDateTime.now(zone)).milliseconds)
            today = LocalDate.now(zone)
        }
    }
    return today
}

/**
 * Milliseconds from [now] to the start of its next local day. Floored at 1 so a
 * clock sitting exactly on midnight (or a backwards DST shift landing us past
 * the boundary) still yields, instead of spinning the wait loop.
 */
internal fun millisUntilNextMidnight(now: ZonedDateTime): Long {
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
    return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
}
