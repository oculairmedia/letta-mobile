package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import java.time.LocalDate
import java.time.ZoneId
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
    fun labelsReadRelativeToToday() {
        val today = LocalDate.of(2026, 3, 4)
        assertEquals("Today", desktopDayLabel(today, today))
        assertEquals("Yesterday", desktopDayLabel(today.minusDays(1), today))
        assertEquals("March 1", desktopDayLabel(LocalDate.of(2026, 3, 1), today))
        assertEquals("December 30, 2025", desktopDayLabel(LocalDate.of(2025, 12, 30), today))
    }
}
