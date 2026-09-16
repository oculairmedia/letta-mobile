package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopChatDayDividersTest {

    private val utc = ZoneId.of("UTC")

    private fun row(id: String, timestamp: String) = DesktopChatRow.Item(
        ChatRenderItem.Single(
            message = UiMessage(id = id, role = "assistant", content = id, timestamp = timestamp),
            groupPosition = GroupPosition.None,
        ),
    )

    private fun List<DesktopChatRow>.dividerDates() =
        filterIsInstance<DesktopChatRow.DayDivider>().map { it.date }

    @Test
    fun singleDayConversationGetsExactlyOneLeadingDivider() {
        // The old hardcoded "Today" header case — behaviour must not regress.
        val rows = withDesktopDayDividers(
            listOf(row("a", "2026-03-04T09:41:00Z"), row("b", "2026-03-04T18:02:00Z")),
            utc,
        )
        assertEquals(3, rows.size)
        assertTrue(rows.first() is DesktopChatRow.DayDivider, "the divider leads the section it opens")
        assertEquals(listOf(LocalDate.of(2026, 3, 4)), rows.dividerDates())
    }

    @Test
    fun eachNewLocalDayOpensItsOwnSection() {
        val rows = withDesktopDayDividers(
            listOf(
                row("a", "2026-03-04T23:50:00Z"),
                row("b", "2026-03-05T00:10:00Z"),
                row("c", "2026-03-05T09:00:00Z"),
                row("d", "2026-03-07T09:00:00Z"),
            ),
            utc,
        )
        assertEquals(
            listOf(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 7)),
            rows.dividerDates(),
        )
        // Order is preserved and no message is dropped.
        assertEquals(
            listOf("msg-a", "msg-b", "msg-c", "msg-d"),
            rows.filterIsInstance<DesktopChatRow.Item>().map { it.key },
        )
    }

    @Test
    fun dayBoundaryIsLocalNotUtc() {
        // 23:30 UTC is already the next day in Tokyo (+09:00): the divider must
        // follow the reader's clock, the same one the "9:41 AM" labels use.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val rows = withDesktopDayDividers(
            listOf(row("a", "2026-03-04T10:00:00Z"), row("b", "2026-03-04T23:30:00Z")),
            tokyo,
        )
        assertEquals(listOf(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5)), rows.dividerDates())
    }

    @Test
    fun unparseableTimestampStaysInTheOpenSection() {
        // A malformed timestamp must cost a divider, not tear a turn in half.
        val rows = withDesktopDayDividers(
            listOf(row("a", "2026-03-04T09:41:00Z"), row("b", "not-a-timestamp"), row("c", "")),
            utc,
        )
        assertEquals(listOf(LocalDate.of(2026, 3, 4)), rows.dividerDates())
        assertEquals(4, rows.size)
    }

    @Test
    fun conversationWithNoReadableTimestampsStillGetsAHeading() {
        val rows = withDesktopDayDividers(listOf(row("a", ""), row("b", "")), utc)
        assertEquals(1, rows.dividerDates().size)
        assertTrue(rows.first() is DesktopChatRow.DayDivider)
    }

    @Test
    fun emptyConversationGetsNoDivider() {
        assertTrue(withDesktopDayDividers(emptyList(), utc).isEmpty())
    }

    @Test
    fun dividerKeysAreStableAndDistinct() {
        val rows = withDesktopDayDividers(
            listOf(row("a", "2026-03-04T09:00:00Z"), row("b", "2026-03-05T09:00:00Z")),
            utc,
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate LazyColumn keys crash the list")
    }

    @Test
    fun midnightWaitEndsExactlyAtTheDayBoundary() {
        val justBefore = ZonedDateTime.of(2026, 3, 4, 23, 59, 59, 0, utc)
        assertEquals(1_000L, millisUntilNextMidnight(justBefore))
        val justAfter = ZonedDateTime.of(2026, 3, 5, 0, 0, 0, 0, utc)
        assertEquals(86_400_000L, millisUntilNextMidnight(justAfter))
    }

    @Test
    fun midnightWaitNeverReturnsZero() {
        // A zero wait would spin the loop; the floor keeps it yielding.
        val boundary = ZonedDateTime.of(2026, 3, 4, 23, 59, 59, 999_999_999, utc)
        assertTrue(millisUntilNextMidnight(boundary) >= 1L)
    }

    @Test
    fun aRepeatedTimestampIsParsedOnlyOnce() {
        // Parsing an ISO timestamp costs ~2µs and the chat asks for the same
        // strings on every recomposition — once per visible message for the clock
        // labels, once per row for every day-divider rebuild, which runs on each
        // streamed token. A wall-clock assertion would flake in CI; counting the
        // parses does not. Uses the production zone so a zone switch elsewhere
        // cannot invalidate the cache mid-test.
        val zone = ZoneId.systemDefault()
        val stamp = IsoTimestamp("2031-04-17T09:41:00Z")
        parseMessageTimestamp(stamp, zone)
        val afterFirst = timestampParseCount
        repeat(50) { parseMessageTimestamp(stamp, zone) }
        assertEquals(afterFirst, timestampParseCount, "a cached timestamp must never be re-parsed")
    }

    @Test
    fun anUnparseableTimestampIsNotRetriedEveryFrame() {
        val zone = ZoneId.systemDefault()
        val junk = IsoTimestamp("not-a-timestamp-9f3a1c")
        parseMessageTimestamp(junk, zone)
        val afterFirst = timestampParseCount
        repeat(50) { parseMessageTimestamp(junk, zone) }
        assertEquals(afterFirst, timestampParseCount, "a failed parse must be remembered too")
    }

    @Test
    fun labelsReadRelativeToToday() {
        val today = LocalDate.of(2026, 3, 4)
        assertEquals("Today", desktopDayLabel(today, today))
        assertEquals("Yesterday", desktopDayLabel(today.minusDays(1), today))
        assertEquals("March 1", desktopDayLabel(LocalDate.of(2026, 3, 1), today))
        assertEquals("December 30, 2025", desktopDayLabel(LocalDate.of(2025, 12, 30), today))
    }
}
