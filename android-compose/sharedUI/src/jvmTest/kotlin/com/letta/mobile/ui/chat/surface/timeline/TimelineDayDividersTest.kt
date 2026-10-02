package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from desktop's DesktopChatDayDividersTest onto kotlinx-datetime (letta-mobile-bglj6.1). */
class TimelineDayDividersTest {

    private val utc = TimeZone.UTC

    private fun item(id: String, timestamp: String) = ChatRenderItem.Single(
        message = UiMessage(id = id, role = "assistant", content = id, timestamp = timestamp),
        groupPosition = GroupPosition.None,
    )

    private fun row(id: String, timestamp: String) = TimelineRow.Item(item(id, timestamp))

    private fun List<TimelineRow>.dividerDates() = filterIsInstance<TimelineRow.DayDivider>().map { it.date }

    @Test
    fun singleDayConversationGetsNoDivider() {
        // As on Android: dividers mark a CHANGE of day, so the oldest day opens without one.
        val rows = withDayDividers(listOf(row("a", "2026-03-04T09:41:00Z"), row("b", "2026-03-04T18:02:00Z")), utc)
        assertEquals(2, rows.size)
        assertTrue(rows.dividerDates().isEmpty())
    }

    @Test
    fun eachNewLocalDayOpensItsOwnSection() {
        val rows = withDayDividers(
            listOf(
                row("a", "2026-03-04T23:50:00Z"),
                row("b", "2026-03-05T00:10:00Z"),
                row("c", "2026-03-05T09:00:00Z"),
                row("d", "2026-03-07T09:00:00Z"),
            ),
            utc,
        )
        assertEquals(listOf(LocalDate(2026, 3, 5), LocalDate(2026, 3, 7)), rows.dividerDates())
        assertTrue(rows[1] is TimelineRow.DayDivider, "the divider leads the day it opens")
        assertEquals(listOf("msg-a", "msg-b", "msg-c", "msg-d"), rows.filterIsInstance<TimelineRow.Item>().map { it.key })
    }

    @Test
    fun dayBoundaryIsLocalNotUtc() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        val rows = withDayDividers(listOf(row("a", "2026-03-04T10:00:00Z"), row("b", "2026-03-04T23:30:00Z")), tokyo)
        assertEquals(listOf(LocalDate(2026, 3, 5)), rows.dividerDates())
    }

    @Test
    fun offsetAndZonelessTimestampsParse() {
        assertEquals(LocalDate(2026, 3, 5), parseTimelineLocalDate("2026-03-04T23:30:00-02:00", utc))
        assertEquals(LocalDate(2026, 3, 4), parseTimelineLocalDate("2026-03-04T23:30:00", utc))
        assertNull(parseTimelineLocalDate("not-a-timestamp", utc))
    }

    @Test
    fun unparseableTimestampStaysInTheOpenSection() {
        val rows = withDayDividers(
            listOf(row("x", "2026-03-03T09:41:00Z"), row("a", "2026-03-04T09:41:00Z"), row("b", "not-a-timestamp"), row("c", "")),
            utc,
        )
        assertEquals(listOf(LocalDate(2026, 3, 4)), rows.dividerDates())
        assertEquals(5, rows.size)
    }

    @Test
    fun conversationWithNoReadableTimestampsGetsNoDivider() {
        val rows = withDayDividers(listOf(row("a", ""), row("b", "")), utc)
        assertTrue(rows.dividerDates().isEmpty())
        assertEquals(2, rows.size)
    }

    @Test
    fun emptyConversationGetsNoDivider() {
        assertTrue(withDayDividers(emptyList(), utc).isEmpty())
    }

    @Test
    fun dividerKeysAreStableAndDistinct() {
        val rows = withDayDividers(listOf(row("a", "2026-03-04T09:00:00Z"), row("b", "2026-03-05T09:00:00Z")), utc)
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate LazyColumn keys crash the list")
    }

    @Test
    fun newestFirstRowsPutEachDividerAboveItsDay() {
        // The reversed list: index 0 is the newest row; a day's divider sits at the index just past
        // that day's OLDEST row, which a reverse layout draws above it.
        // Read in UTC, so the machine's zone cannot move a day boundary.
        val newestFirst = listOf(
            item("c", "2026-03-05T12:00:00Z"),
            item("b", "2026-03-04T13:00:00Z"),
            item("a", "2026-03-04T12:00:00Z"),
        )
        assertEquals(
            listOf("msg-c", "__day__2026-03-05", "msg-b", "msg-a"),
            timelineRowsNewestFirst(newestFirst, utc).map { it.key },
        )
    }

    @Test
    fun midnightWaitEndsExactlyAtTheDayBoundary() {
        val justBefore = LocalDateTime(2026, 3, 4, 23, 59, 59).toInstant(utc)
        assertEquals(1_000L, millisUntilNextMidnight(justBefore, utc))
        val justAfter = LocalDateTime(2026, 3, 5, 0, 0, 0).toInstant(utc)
        assertEquals(86_400_000L, millisUntilNextMidnight(justAfter, utc))
    }

    @Test
    fun midnightWaitNeverReturnsZero() {
        val boundary = LocalDateTime(2026, 3, 4, 23, 59, 59, 999_999_999).toInstant(utc)
        assertTrue(millisUntilNextMidnight(boundary, utc) >= 1L)
    }

    @Test
    fun labelsReadRelativeToToday() {
        val today = LocalDate(2026, 3, 4)
        assertEquals(DayLabel.Today, dayLabelOf(today, today))
        assertEquals(DayLabel.Yesterday, dayLabelOf(LocalDate(2026, 3, 3), today))
        assertEquals(DayLabel.Date("March 1"), dayLabelOf(LocalDate(2026, 3, 1), today))
        assertEquals(DayLabel.Date("December 30, 2025"), dayLabelOf(LocalDate(2025, 12, 30), today))
    }

    // Paged rows: ported from desktop's DesktopCanonicalMessageListTest.

    @Test
    fun theFirstRowOfADayCarriesTheDivider() {
        assertEquals(
            LocalDate(2026, 9, 12),
            pagedBoundaryDate(item("a", "2026-09-12T09:00:00Z"), item("b", "2026-09-11T23:50:00Z"), utc),
        )
    }

    @Test
    fun aRowSharingItsPredecessorsDayCarriesNoDivider() {
        assertNull(pagedBoundaryDate(item("a", "2026-09-12T09:00:00Z"), item("b", "2026-09-12T01:00:00Z"), utc))
    }

    @Test
    fun theOldestResidentRowOpensNoDivider() {
        assertNull(pagedBoundaryDate(item("a", "2026-09-12T09:00:00Z"), older = null, zone = utc))
    }

    @Test
    fun anUnparseableTimestampYieldsNoDivider() {
        assertNull(pagedBoundaryDate(item("a", ""), older = null, zone = utc))
        assertNull(pagedBoundaryDate(item("a", "not-a-date"), older = null, zone = utc))
    }
}
