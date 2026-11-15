package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-o1hju S1 (hypothesis H1, invariant I1): settled rows are grouped per page
 * (TimelinePageProjectionInput.aggregatePreparedRuns), so `run-<id>` is unique only within a page.
 * The phone logged timeline.duplicateKeyDropped with sources=settled+settled for one run key
 * (run-local-run-125 x30), each one a row the guard blanked.
 *
 * Each case puts one finished run in the ledger, then enough later history that the newest
 * settled page (64 rows) starts at a chosen row of that run, so the run is cut across two pages.
 * The real coordinator, writer, Paging and presentation present it; the reader scrolls back over
 * both pages ([TimelineFrameRecorder.readAllSettledPages]). No two resident settled rows may share
 * a key, and no frame may hold a row the guard had to blank.
 */
class TimelineRunKeyAcrossPagesTest {
    /** (a) One multi-step run, each step closed by its own usage and stop reason. */
    @Test fun aRunCutAcrossTwoPagesNeverRepeatsAKey() = assertEveryCutIsUnique(
        listOf(
            prompt, thought, call, callReturn, narration, usage("u-1", 5), stop("s-1", 5),
            secondCall, secondReturn, reply, usage("u-2", 10), stop("s-2", 10),
        ),
    )

    /** (b) The #2460 shape: a follow-up prompt lands while the run is still streaming. */
    @Test fun aRunInterruptedByAFollowUpPromptNeverRepeatsAKeyAcrossPages() = assertEveryCutIsUnique(
        listOf(
            prompt, thought, call, callReturn, narration, followUp, secondCall, secondReturn, reply,
            usage("u-2", 10), stop("s-2", 10),
        ),
    )

    /**
     * I7: the same record set always presents the same keys, so a page prepared again (a new
     * generation over unchanged rows) remounts nothing, wherever the page edge cuts the run.
     */
    @Test fun theSameRecordsAlwaysPresentTheSameKeys() = runBlocking {
        val turn = listOf(
            prompt, thought, call, callReturn, narration, usage("u-1", 5), stop("s-1", 5), followUp,
            secondCall, secondReturn, reply, usage("u-2", 10), stop("s-2", 10),
        )
        val rows = rowsOf(turn)
        for (cut in 1 until rows.size) {
            val first = presentCutAt(turn, rows, cut) { it.settledKeys() }
            val second = presentCutAt(turn, rows, cut) { it.settledKeys() }
            assertEquals(first, second, "cut before ${rows[cut]}: the same records presented different keys")
        }
    }

    private fun assertEveryCutIsUnique(turn: List<LettaMessage>) = runBlocking {
        val rows = rowsOf(turn)
        val violations = (1 until rows.size).flatMap { cut -> duplicatesWhenCutAt(turn, rows, cut) }
        assertTrue(violations.isEmpty(), "duplicate settled keys (sources=settled+settled):\n" + violations.joinToString("\n"))
    }

    /**
     * Tool returns fold into their call's row, so they are no row of their own. The ledger
     * orders rows by time, then identity (TimelinePageKey).
     */
    private fun rowsOf(turn: List<LettaMessage>): List<String> =
        turn.filterNot { it is ToolReturnMessage }.sortedWith(compareBy({ Instant.parse(it.date) }, { it.id })).map { it.id }

    private suspend fun duplicatesWhenCutAt(turn: List<LettaMessage>, rows: List<String>, cut: Int): List<String> =
        presentCutAt(turn, rows, cut) { recorder ->
            val keys = recorder.settledKeys()
            val repeated = keys.filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }
            val blanked = recorder.frames.flatMap { frame ->
                frame.keys.filter { DUPLICATE_MARK in it }.map { "frame ${frame.index} [${frame.step}] blanked $it" }
            }.distinct()
            val where = "cut before ${rows[cut]}"
            repeated.map { (key, count) -> "$where: $key held by $count settled rows" } + blanked.map { "$where: $it" }
        }

    /** The run's first [cut] rows sit on the older page, the rest on the newest one; the reader has read both. */
    private suspend fun <T> presentCutAt(
        turn: List<LettaMessage>,
        rows: List<String>,
        cut: Int,
        read: (TimelineFrameRecorder) -> T,
    ): T {
        val recorder = TimelineFrameRecorder.open(scope)
        try {
            recorder.openOnHistory((history + turn).reversed())
            recorder.reconcile(later(PAGE_ROWS - (rows.size - cut)).reversed(), step = "later history")
            assertEquals(
                rows[cut], recorder.newestPageIdentities().first(),
                "fixture: the newest page must start at row $cut of the run",
            )
            recorder.readAllSettledPages()
            return read(recorder)
        } finally {
            recorder.close()
        }
    }

    private companion object {
        const val RUN = "local-run-125"
        /** CanonicalTimelineCoordinator's page: TimelinePageBudget.maxMetadataRows. */
        const val PAGE_ROWS = 64
        /** What TimelineRowKeyGuard appends to the key of a row it blanks. */
        const val DUPLICATE_MARK = "#duplicate-"
        val scope = TimelineScope("backend", "conv-run-pages", "agent")
        val turnStart: Instant = Instant.parse("2026-11-14T15:54:00Z")

        fun at(seconds: Long): String = turnStart.plusSeconds(seconds).toString()

        val history: List<LettaMessage> = listOf(
            UserMessage(id = "old-prompt", contentRaw = JsonPrimitive("Earlier question"), date = at(-600), otid = "cm-old"),
            AssistantMessage(id = "old-reply", contentRaw = JsonPrimitive("Earlier answer."), date = at(-599)),
        )
        val prompt = UserMessage(id = "msg-prompt", contentRaw = JsonPrimitive("check the build"), date = at(0), otid = "cm-prompt")
        val thought = ReasoningMessage(id = "msg-thought", reasoning = "Look at the log first.", runId = RUN, date = at(1))
        val call = ToolCallMessage(
            id = "msg-call", toolCalls = listOf(ToolCall(id = "call-1", name = "Bash", arguments = "make")), runId = RUN, date = at(2),
        )
        val callReturn = ToolReturnMessage(
            id = "msg-call-return", toolCallId = "call-1", toolReturnRaw = JsonPrimitive("ok"), status = "success",
            runId = RUN, date = at(3),
        )
        val narration = AssistantMessage(id = "msg-narration", contentRaw = JsonPrimitive("Build is green."), runId = RUN, date = at(4))
        val followUp = UserMessage(id = "msg-follow-up", contentRaw = JsonPrimitive("and the tests?"), date = at(6), otid = "cm-follow-up")
        val secondCall = ToolCallMessage(
            id = "msg-call-2", toolCalls = listOf(ToolCall(id = "call-2", name = "Bash", arguments = "make test")),
            runId = RUN, date = at(7),
        )
        val secondReturn = ToolReturnMessage(
            id = "msg-call-2-return", toolCallId = "call-2", toolReturnRaw = JsonPrimitive("pass"), status = "success",
            runId = RUN, date = at(8),
        )
        val reply = AssistantMessage(id = "msg-reply", contentRaw = JsonPrimitive("Tests pass."), runId = RUN, date = at(9))

        fun usage(id: String, second: Long) =
            UsageStatistics(id = id, promptTokens = 1_000, completionTokens = 14, runId = RUN, date = at(second))
        fun stop(id: String, second: Long) = StopReason(id = id, reason = "end_turn", runId = RUN, date = at(second))

        /** [rows] later rows of run-less exchanges, oldest first. */
        fun later(rows: Int): List<LettaMessage> = (0 until rows).map { index ->
            val date = at(600L + index)
            if (index % 2 == 0) {
                UserMessage(
                    id = "later-prompt-$index", contentRaw = JsonPrimitive("question $index"), date = date, otid = "cm-later-$index",
                )
            } else {
                AssistantMessage(id = "later-reply-$index", contentRaw = JsonPrimitive("answer $index"), date = date)
            }
        }
    }
}
