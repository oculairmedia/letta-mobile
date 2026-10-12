package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-om38i: every key the legacy timeline hands its LazyColumn is unique, whatever non-message
 * rows sit between the days. The desktop crash was `Key "__day__2026-10-12" was already used` with a
 * "Yesterday" chip, a "Conversation compacted" divider, then a "Today" chip: chat order is not
 * timestamp order, and the day divider used to reopen on ANY change of day.
 */
class TimelineRowKeyUniquenessTest {

    private val utc = TimeZone.UTC
    private val yesterday = LocalDate(2026, 10, 11)
    private val today = LocalDate(2026, 10, 12)

    private fun single(message: UiMessage) = ChatRenderItem.Single(message, GroupPosition.None)

    private fun user(id: String, ts: String, pending: Boolean = false) =
        single(UiMessage(id = id, role = "user", content = id, timestamp = ts, isPending = pending, clientMessageId = "otid-$id"))

    private fun assistant(id: String, ts: String) = single(UiMessage(id = id, role = "assistant", content = id, timestamp = ts))

    private fun compaction(id: String, ts: String) =
        single(UiMessage(id = id, role = "assistant", content = "summary", timestamp = ts, isCompaction = true))

    private fun error(id: String, ts: String) =
        single(UiMessage(id = id, role = "assistant", content = "run failed", timestamp = ts, isError = true))

    private fun toolOnly(id: String, ts: String, runId: String) = ChatRenderItem.Single(
        message = UiMessage(
            id = id,
            role = "assistant",
            content = "",
            timestamp = ts,
            runId = runId,
            toolCalls = listOf(UiToolCall(name = "Bash", arguments = "{}", result = "ok", status = "completed")),
        ),
        groupPosition = GroupPosition.None,
        stableRunId = runId,
    )

    /** A run whose steps straddle midnight: its boundary (newest step) reads as today. */
    private fun runAcrossMidnight(runId: String) = ChatRenderItem.RunBlock(
        runId = runId,
        messages = listOf(
            UiMessage(id = "$runId-1", role = "assistant", content = "step 1", timestamp = "2026-10-11T23:50:00Z", runId = runId) to
                GroupPosition.First,
            UiMessage(id = "$runId-2", role = "assistant", content = "step 2", timestamp = "2026-10-12T00:10:00Z", runId = runId) to
                GroupPosition.Last,
        ),
    )

    /** Builds the rows exactly as LegacyTimelineList does, with both edge rows on. */
    private fun lazyColumnKeys(chatOrder: List<ChatRenderItem>): Pair<List<TimelineRow>, List<String>> {
        val rows = timelineRowsNewestFirst(chatOrder.asReversed(), utc)
        return rows to (listOf(THINKING_KEY) + rows.map { it.key } + LOADING_OLDER_KEY)
    }

    private fun assertUnique(keys: List<String>) {
        val dupes = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate LazyColumn keys crash the list: $dupes in $keys")
    }

    // Rows are newest-first (reversed layout); read the dividers in chat order.
    private fun List<TimelineRow>.dividerDates() = asReversed().filterIsInstance<TimelineRow.DayDivider>().map { it.date }

    @Test
    fun compactionStampedYesterdayBetweenTodaysRowsOpensTodayOnce() {
        // The reported shape: yesterday, today, the compaction summary (stamped yesterday), today.
        val (rows, keys) = lazyColumnKeys(
            listOf(
                user("y1", "2026-10-11T21:00:00Z"),
                assistant("y2", "2026-10-11T21:01:00Z"),
                user("t1", "2026-10-12T00:20:00Z"),
                compaction("c1", "2026-10-11T23:55:00Z"),
                user("t2", "2026-10-12T01:00:00Z"),
                assistant("t3", "2026-10-12T01:01:00Z"),
            ),
        )
        assertUnique(keys)
        assertEquals(listOf(today), rows.dividerDates(), "the oldest day opens bare; today opens once")
        assertEquals(
            listOf("msg-otid-y1", "msg-y2", "__day__2026-10-12", "msg-otid-t1", "msg-c1", "msg-otid-t2", "msg-t3"),
            rows.asReversed().map { it.key },
            "no row moves: the earlier-stamped compaction stays in the open section",
        )
    }

    @Test
    fun runBlockSpanningMidnightThenACompactionAndTodaysRows() {
        // A run block reads as its NEWEST step, so it opens today ahead of an older-stamped row.
        val (rows, keys) = lazyColumnKeys(
            listOf(
                user("y1", "2026-10-11T23:45:00Z"),
                runAcrossMidnight("run-a"),
                compaction("c1", "2026-10-11T23:59:00Z"),
                user("t1", "2026-10-12T09:00:00Z"),
            ),
        )
        assertUnique(keys)
        assertEquals(listOf(today), rows.dividerDates())
    }

    @Test
    fun kitchenSinkOfNonMessageRowsKeepsEveryKeyUnique() {
        // Compaction, a folded tool group, an error row, a queued (pending) prompt, a run block
        // spanning midnight, plus the thinking and loading-older edge rows, across three days.
        val (rows, keys) = lazyColumnKeys(
            listOf(
                user("d1", "2026-10-10T10:00:00Z"),
                toolOnly("tool-1", "2026-10-10T10:01:00Z", runId = "run-t"),
                toolOnly("tool-2", "2026-10-10T10:02:00Z", runId = "run-t"),
                compaction("c-old", "2026-10-10T09:00:00Z"),
                user("y1", "2026-10-11T23:45:00Z"),
                runAcrossMidnight("run-a"),
                compaction("c-new", "2026-10-11T23:59:00Z"),
                error("err", "2026-10-11T23:59:30Z"),
                user("t1", "2026-10-12T09:00:00Z"),
                user("queued", "2026-10-12T09:05:00Z", pending = true),
            ),
        )
        assertUnique(keys)
        assertTrue(rows.any { it is TimelineRow.ToolGroup }, "the fixture really folds a tool group")
        assertEquals(listOf(yesterday, today), rows.dividerDates())
    }

    @Test
    fun anyOrderOfDaysYieldsForwardOnlyUniqueDividers() {
        // Exhaustive over every assignment of three days to five rows (3^5): whatever the projection
        // interleaves, dividers only move forward and no date's key repeats.
        val days = listOf("2026-10-10", "2026-10-11", "2026-10-12")
        val total = 243
        for (mask in 0 until total) {
            var m = mask
            val items = (0 until 5).map { i ->
                val day = days[m % 3].also { m /= 3 }
                assistant("m$i", "${day}T12:0$i:00Z")
            }
            val (rows, keys) = lazyColumnKeys(items)
            assertUnique(keys)
            val dates = rows.dividerDates()
            assertEquals(dates.sorted().distinct(), dates, "dividers must be strictly increasing for mask $mask")
        }
    }

    @Test
    fun pagedRouteNeverNamesADayBehindItsOlderNeighbour() {
        // The paged route draws its chip inside the row (no LazyColumn key), but the same rule holds:
        // a compaction stamped yesterday after a today row does not draw a second "Yesterday".
        assertNull(pagedBoundaryDate(compaction("c1", "2026-10-11T23:55:00Z"), user("t1", "2026-10-12T00:20:00Z"), utc))
        assertEquals(today, pagedBoundaryDate(user("t1", "2026-10-12T00:20:00Z"), user("y1", "2026-10-11T21:00:00Z"), utc))
    }
}
