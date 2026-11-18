package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.chat.surface.timeline.days.TimelineDays
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Day sections, lifted from desktop's DesktopChatDayDividers and rewritten on
 * kotlinx-datetime so it runs in commonMain.
 *
 * Inserts a [TimelineRow.DayDivider] where the LOCAL day changes, ahead of the new day's first
 * row, as the Android timeline does: the oldest day opens with no divider, so a conversation that
 * fits in one day shows none. Rows whose timestamp is blank or unparseable emit no divider and
 * stay in the open section, so a malformed timestamp cannot tear a turn in half. Input and output
 * are in chat order (oldest first).
 *
 * sections only ever move FORWARD. Chat order is not timestamp order: a
 * compaction's summary row can carry an earlier stamp than the rows around it, and a run block's
 * boundary is its NEWEST step, so a run spanning midnight reads as tomorrow while the row after it
 * reads as today. Opening a section on any change re-emitted the same day's divider (yesterday,
 * compaction, today,..., today) and the duplicate `__day__<date>` key crashed the LazyColumn. A
 * row stamped earlier than the open section stays in that section, exactly like an unreadable
 * stamp, so each date opens at most one section and every divider key is unique by construction.
 */
internal fun withDayDividers(
    chatOrderRows: List<TimelineRow>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    days: TimelineDays = TimelineDays(zone),
): List<TimelineRow> {
    if (chatOrderRows.isEmpty()) return chatOrderRows
    val out = ArrayList<TimelineRow>(chatOrderRows.size + 1)
    var currentDay: LocalDate? = null
    chatOrderRows.forEach { row ->
        val day = row.timestampOrNull()?.let(days::of)
        if (day != null && opensNewSection(day, currentDay)) {
            if (currentDay != null) out += TimelineRow.DayDivider(day)
            currentDay = day
        }
        out += row
    }
    return out
}

/** Only a strictly LATER day opens a section; an earlier-stamped row stays in the open one. */
private fun opensNewSection(day: LocalDate, currentDay: LocalDate?): Boolean = currentDay == null || day > currentDay

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
    days: TimelineDays = TimelineDays(zone),
): LocalDate? {
    if (older == null) return null
    val newerDay = days.of(newer.boundaryTimestamp) ?: return null
    val olderDay = days.of(older.boundaryTimestamp)
    // letta-mobile-om38i: forward only, as in [withDayDividers]; an earlier-stamped row (a
    // compaction summary) never names a day behind the one its older neighbour already opened.
    // letta-mobile-ivsx0: an undated neighbour (a deferred body) is no evidence of a new day; it
    // stays in the open section, as an unreadable stamp does in [withDayDividers].
    val olderKnown = olderDay ?: return null
    return newerDay.takeIf { it > olderKnown }
}
