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

/** A UTC instant on a fixture day: fixtures name a day and a clock time, never a raw ISO string. */
@JvmInline
private value class At(val iso: String)

/** A server run id, typed so it cannot be swapped with a message id. */
@JvmInline
private value class Run(val id: String)

private fun LocalDate.at(clock: String) = At("${this}T$clock:00Z")

private val TwoDaysAgo = LocalDate(2026, 10, 10)
private val Yesterday = LocalDate(2026, 10, 11)
private val Today = LocalDate(2026, 10, 12)

private fun d(clock: String) = TwoDaysAgo.at(clock)
private fun y(clock: String) = Yesterday.at(clock)
private fun t(clock: String) = Today.at(clock)

private fun message(id: String, at: At) = UiMessage(id = id, role = "assistant", content = id, timestamp = at.iso)

private fun single(message: UiMessage) = ChatRenderItem.Single(message, GroupPosition.None)

private fun user(id: String, at: At) = single(message(id, at).copy(role = "user", clientMessageId = "otid-$id"))

private fun queued(id: String, at: At) =
    single(message(id, at).copy(role = "user", isPending = true, clientMessageId = "otid-$id"))

private fun assistant(id: String, at: At) = single(message(id, at))

private fun compaction(id: String, at: At) = single(message(id, at).copy(content = "summary", isCompaction = true))

private fun error(id: String, at: At) = single(message(id, at).copy(content = "run failed", isError = true))

private fun toolOnly(id: String, at: At, run: Run) = ChatRenderItem.Single(
    message = message(id, at).copy(
        content = "",
        runId = run.id,
        toolCalls = listOf(UiToolCall(name = "Bash", arguments = "{}", result = "ok", status = "completed")),
    ),
    groupPosition = GroupPosition.None,
    stableRunId = run.id,
)

/** A run whose steps straddle midnight: its boundary (newest step) reads as today. */
private fun runAcrossMidnight(run: Run) = ChatRenderItem.RunBlock(
    runId = run.id,
    messages = listOf(
        message("${run.id}-1", y("23:50")).copy(runId = run.id) to GroupPosition.First,
        message("${run.id}-2", t("00:10")).copy(runId = run.id) to GroupPosition.Last,
    ),
)

/** The rows LegacyTimelineList builds, plus every key its LazyColumn receives (edge rows on). */
private class BuiltTimeline(chatOrder: List<ChatRenderItem>) {
    /** Newest-first, as the reversed list holds them. */
    val rows: List<TimelineRow> = timelineRowsNewestFirst(chatOrder.asReversed(), TimeZone.UTC)
    val lazyColumnKeys: List<String> = listOf(THINKING_KEY) + rows.map { it.key } + LOADING_OLDER_KEY
    val chatOrderKeys: List<String> get() = rows.asReversed().map { it.key }
    val dividerDates: List<LocalDate>
        get() = rows.asReversed().filterIsInstance<TimelineRow.DayDivider>().map { it.date }

    fun assertKeysUnique() {
        val dupes = lazyColumnKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate LazyColumn keys crash the list: $dupes in $lazyColumnKeys")
    }
}

/**
 * letta-mobile-om38i: every key the legacy timeline hands its LazyColumn is unique, whatever non-message
 * rows sit between the days. The desktop crash was `Key "__day__2026-10-12" was already used` with a
 * "Yesterday" chip, a "Conversation compacted" divider, then a "Today" chip: chat order is not
 * timestamp order, and the day divider used to reopen on ANY change of day.
 */
class TimelineRowKeyUniquenessTest {

    @Test
    fun compactionStampedYesterdayBetweenTodaysRowsOpensTodayOnce() {
        // The reported shape: yesterday, today, the compaction summary (stamped yesterday), today.
        val timeline = BuiltTimeline(
            listOf(
                user("y1", y("21:00")),
                assistant("y2", y("21:01")),
                user("t1", t("00:20")),
                compaction("c1", y("23:55")),
                user("t2", t("01:00")),
                assistant("t3", t("01:01")),
            ),
        )
        timeline.assertKeysUnique()
        assertEquals(listOf(Today), timeline.dividerDates, "the oldest day opens bare; today opens once")
        assertEquals(
            listOf("msg-otid-y1", "msg-y2", "__day__2026-10-12", "msg-otid-t1", "msg-c1", "msg-otid-t2", "msg-t3"),
            timeline.chatOrderKeys,
            "no row moves: the earlier-stamped compaction stays in the open section",
        )
    }

    @Test
    fun twoCompactionsOnTheSameDayEmitEachDayHeaderOnce() {
        // The second reported screenshot: compacted, messages, Yesterday, compacted again, Today,
        // messages, with two compactions landing today (each stamped earlier than its neighbours).
        // The old builder re-emitted the day header after every compaction: __day__2026-10-12 x3.
        val timeline = BuiltTimeline(
            listOf(
                compaction("c0", d("08:00")),
                user("d1", d("09:00")),
                user("y1", y("10:00")),
                compaction("c1", y("09:30")),
                user("t1", t("08:00")),
                compaction("c2", y("23:00")),
                assistant("t2", t("09:00")),
                compaction("c3", y("23:30")),
                user("t3", t("10:00")),
            ),
        )
        timeline.assertKeysUnique()
        assertEquals(listOf(Yesterday, Today), timeline.dividerDates, "one header per calendar day")
        assertEquals(
            listOf(
                "msg-c0", "msg-otid-d1",
                "__day__2026-10-11", "msg-otid-y1", "msg-c1",
                "__day__2026-10-12", "msg-otid-t1", "msg-c2", "msg-t2", "msg-c3", "msg-otid-t3",
            ),
            timeline.chatOrderKeys,
            "each day chip sits once at its first row; compaction dividers stay inline where they were",
        )
    }

    @Test
    fun runBlockSpanningMidnightThenACompactionAndTodaysRows() {
        // A run block reads as its NEWEST step, so it opens today ahead of an older-stamped row.
        val timeline = BuiltTimeline(
            listOf(
                user("y1", y("23:45")),
                runAcrossMidnight(Run("run-a")),
                compaction("c1", y("23:59")),
                user("t1", t("09:00")),
            ),
        )
        timeline.assertKeysUnique()
        assertEquals(listOf(Today), timeline.dividerDates)
    }

    @Test
    fun kitchenSinkOfNonMessageRowsKeepsEveryKeyUnique() {
        // Compaction, a folded tool group, an error row, a queued (pending) prompt, a run block
        // spanning midnight, plus the thinking and loading-older edge rows, across three days.
        val toolRun = Run("run-t")
        val timeline = BuiltTimeline(
            listOf(
                user("d1", d("10:00")),
                toolOnly("tool-1", d("10:01"), toolRun),
                toolOnly("tool-2", d("10:02"), toolRun),
                compaction("c-old", d("09:00")),
                user("y1", y("23:45")),
                runAcrossMidnight(Run("run-a")),
                compaction("c-new", y("23:59")),
                error("err", y("23:59")),
                user("t1", t("09:00")),
                queued("queued", t("09:05")),
            ),
        )
        timeline.assertKeysUnique()
        assertTrue(timeline.rows.any { it is TimelineRow.ToolGroup }, "the fixture really folds a tool group")
        assertEquals(listOf(Yesterday, Today), timeline.dividerDates)
    }

    @Test
    fun anyOrderOfDaysYieldsForwardOnlyUniqueDividers() {
        // Exhaustive over every assignment of three days to five rows (3^5): whatever the projection
        // interleaves, dividers only move forward and no date's key repeats.
        val days = listOf(TwoDaysAgo, Yesterday, Today)
        for (mask in 0 until 243) {
            var m = mask
            val items = (0 until 5).map { i ->
                val day = days[m % 3].also { m /= 3 }
                assistant("m$i", day.at("12:0$i"))
            }
            val timeline = BuiltTimeline(items)
            timeline.assertKeysUnique()
            val dates = timeline.dividerDates
            assertEquals(dates.sorted().distinct(), dates, "dividers must be strictly increasing for mask $mask")
        }
    }

    @Test
    fun pagedRouteNeverNamesADayBehindItsOlderNeighbour() {
        // The paged route draws its chip inside the row (no LazyColumn key), but the same rule holds:
        // a compaction stamped yesterday after a today row does not draw a second "Yesterday".
        assertNull(pagedBoundaryDate(compaction("c1", y("23:55")), user("t1", t("00:20")), TimeZone.UTC))
        assertEquals(Today, pagedBoundaryDate(user("t1", t("00:20")), user("y1", y("21:00")), TimeZone.UTC))
    }
}
