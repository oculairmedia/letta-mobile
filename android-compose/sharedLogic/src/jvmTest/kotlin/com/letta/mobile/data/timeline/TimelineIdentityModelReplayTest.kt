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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-o1hju: counterexamples the TLA+ model (verification/timeline-identity, run with
 * check.sh) found that the S1 fixtures (TimelineRunKeyAcrossPagesTest) do not cover, replayed
 * against the real coordinator, writer, Paging and presentation ([TimelineFrameRecorder]).
 *
 * [InMemoryTimelineStore] reports page edges as the production stores do: a page's older/newer key
 * is its own first/last row (RoomTimelineBoundedStore, the desktop store), which
 * TimelineLedgerPagingSource hands to Before/After for the next page.
 */
class TimelineIdentityModelReplayTest {
    /**
     * I1 NoDuplicateKey, model traces main-live-a / d1-live-a, in the code's words:
     * 1. The turn streams live; projectLiveItems keys the run block `run-<live run>`.
     * 2. The durable turn lands; aggregatePreparedRuns claims the run on one page and
     *    SettledLiveAdoptions.adoptLocked lends it the live key, remembering that key under EVERY
     *    member identity (byIdentity).
     * 3. Later history moves the page edge into the run; the next generation presents the run as
     *    two rows, one per page.
     * 4. Each row finds a member in byIdentity and takes the remembered key; that path skips the
     *    holders check, so the live key is listed twice and TimelineRowKeyGuard blanks one row.
     * This holds whatever key the page claims, so D1 alone does not remove it. The key the phone
     * logged (run-local-run-125) is a live run id: App Server rows carry no run id.
     */
    @Test fun aRunThatTookItsLiveKeyKeepsItOnOneRowAfterThePageEdgeMovesIntoIt() = runBlocking {
        val violations = (1 until turnRows.size).flatMap { cut -> adoptedKeyDuplicatesWhenCutAt(cut) }
        assertTrue(violations.isEmpty(), "duplicate keys after the live handover:\n" + violations.joinToString("\n"))
    }

    /**
     * I1 NoDuplicateKey, model trace main-settled-b (cut 1..3 | 4..): the #2460 shape cut right
     * before the run-less follow-up prompt. runEnvelope answers Ends on both sides of the edge (a
     * prompt has no run), containsComplete holds on both pages and both claim `run-<id>`.
     * TimelineRunKeyAcrossPagesTest once saw this cut green only because the in-memory store then
     * reported the neighbour's key as the page edge, so its older page skipped the narration row.
     */
    @Test fun aRunCutAtItsFollowUpPromptNeverRepeatsAKey() = runBlocking {
        val turn = listOf(prompt, call, callReturn, narration, followUp, secondCall, secondReturn, reply, usage, stop)
            .map { it.withRun(DURABLE_RUN) }
        val rows = turn.ledgerRows()
        val cut = rows.indexOf(followUp.id)
        val recorder = TimelineFrameRecorder.open(scope, InMemoryTimelineStore())
        val violations = try {
            recorder.openOnHistory((history + turn).reversed())
            // The ledger keeps what it holds; the next recent page brings only the later rows.
            recorder.reconcile(later(PAGE_ROWS - (rows.size - cut)).reversed(), step = "later history")
            assertEquals(rows[cut], recorder.newestPageIdentities().first(), "fixture: the newest page must start at the follow-up")
            recorder.readAllSettledPages()
            duplicates(recorder, "cut before ${rows[cut]}")
        } finally {
            recorder.close()
        }
        assertTrue(violations.isEmpty(), "duplicate settled keys:\n" + violations.joinToString("\n"))
    }

    /**
     * I5 NoVanishedRow, model trace d1-live-a (and main-live-a), in the code's words:
     * 1. The turn streams live; the overlay draws the run as one block keyed `run-<live run>`.
     * 2. While it still streams, the Paging mediator's recent-page catch-up makes only the first rows
     *    of the run durable (a catch-up is not deferred behind the live fence). The settled page
     *    claims them and adopts the live block's key (livePresentationFor matches by any member).
     * 3. TimelineRowAssembly.displayedLive hides every live row whose key a settled row holds, so
     *    the whole live block goes, and the rows that are not durable yet leave the list.
     */
    @Test fun aPartlyDurableRunNeverHidesItsLiveRows() = runBlocking {
        val recorder = TimelineFrameRecorder.open(scope, InMemoryTimelineStore())
        try {
            recorder.openOn(history.reversed())
            recorder.send(PROMPT_OTID, prompt.content, echo.date!!)
            recorder.stream(listOf(echo) + liveTurn, finished = false)
            val streamed = recorder.lastFrame()
            recorder.catchUp((history + listOf(prompt, thought, call, callReturn)).reversed(), step = "catch-up mid-turn")
            // Paging presents the new generation on its own schedule: wait until the prompt is settled.
            withTimeout(PRESENT_TIMEOUT_MS) {
                while (recorder.settledKeys().size <= history.size) delay(PRESENT_POLL_MS)
            }
            recorder.readAllSettledPages()
            val after = recorder.lastFrame()
            val drawn = after.rows.flatMap { it.messages }.toSet()
            val gone = streamed.rows.flatMap { it.messages }.filter { it !in drawn }
            assertTrue(
                gone.isEmpty(),
                "rows drawn while streaming left the list after a partial catch-up: $gone\n" +
                    "before: $streamed\nafter:  $after",
            )
        } finally {
            recorder.close()
        }
    }

    private suspend fun adoptedKeyDuplicatesWhenCutAt(cut: Int): List<String> {
        val recorder = TimelineFrameRecorder.open(scope, InMemoryTimelineStore())
        try {
            recorder.openOn(history.reversed())
            recorder.send(PROMPT_OTID, prompt.content, echo.date!!)
            recorder.stream(listOf(echo) + liveTurn)
            recorder.settle((history + durableTurn).reversed())
            val handedOver = duplicates(recorder, "after the handover")
            recorder.reconcile(later(PAGE_ROWS - (turnRows.size - cut)).reversed(), step = "later history")
            assertEquals(
                turnRows[cut], recorder.newestPageIdentities().first(),
                "fixture: the newest page must start at row $cut of the turn",
            )
            recorder.readAllSettledPages()
            return handedOver + duplicates(recorder, "cut before ${turnRows[cut]}")
        } finally {
            recorder.close()
        }
    }

    private fun duplicates(recorder: TimelineFrameRecorder, where: String): List<String> {
        val repeated = recorder.settledKeys().filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }
        val blanked = recorder.frames.flatMap { frame ->
            frame.keys.filter { DUPLICATE_MARK in it }.map { "frame ${frame.index} [${frame.step}] blanked $it" }
        }.distinct()
        return repeated.map { (key, count) -> "$where: $key held by $count settled rows" } + blanked.map { "$where: $it" }
    }

    private companion object {
        const val PROMPT_OTID = "cm-replay-1"
        const val LIVE_RUN = "local-run-125"
        const val DURABLE_RUN = "run-durable-125"
        /** CanonicalTimelineCoordinator's page: TimelinePageBudget.maxMetadataRows. */
        const val PAGE_ROWS = 64
        /** What TimelineRowKeyGuard appends to the key of a row it blanks. */
        const val DUPLICATE_MARK = "#duplicate-"
        const val PRESENT_TIMEOUT_MS = 10_000L
        const val PRESENT_POLL_MS = 10L
        val scope = TimelineScope("backend", "conv-model-replay", "agent")
        val turnStart: Instant = Instant.parse("2026-11-14T15:54:00Z")

        fun at(seconds: Long): String = turnStart.plusSeconds(seconds).toString()

        val history: List<LettaMessage> = listOf(
            UserMessage(id = "old-prompt", contentRaw = JsonPrimitive("Earlier question"), date = at(-600), otid = "cm-old"),
            AssistantMessage(id = "old-reply", contentRaw = JsonPrimitive("Earlier answer."), date = at(-599)),
        )
        val prompt = UserMessage(id = "msg-prompt", contentRaw = JsonPrimitive("check the build"), date = at(0), otid = PROMPT_OTID)
        val echo = prompt.copy(id = "cm-user-$PROMPT_OTID", date = at(1))
        val thought = ReasoningMessage(id = "msg-thought", reasoning = "Look at the log first.", date = at(2))
        val call = ToolCallMessage(
            id = "msg-call", toolCalls = listOf(ToolCall(id = "call-1", name = "Bash", arguments = "make")), date = at(3),
        )
        val callReturn = ToolReturnMessage(
            id = "msg-call-return", toolCallId = "call-1", toolReturnRaw = JsonPrimitive("ok"), status = "success", date = at(4),
        )
        val narration = AssistantMessage(id = "msg-narration", contentRaw = JsonPrimitive("Build is green."), date = at(5))
        val followUp = UserMessage(id = "msg-follow-up", contentRaw = JsonPrimitive("and the tests?"), date = at(6), otid = "cm-follow-up")
        val secondCall = ToolCallMessage(
            id = "msg-call-2", toolCalls = listOf(ToolCall(id = "call-2", name = "Bash", arguments = "make test")), date = at(7),
        )
        val secondReturn = ToolReturnMessage(
            id = "msg-call-2-return", toolCallId = "call-2", toolReturnRaw = JsonPrimitive("pass"), status = "success", date = at(8),
        )
        val reply = AssistantMessage(id = "msg-reply", contentRaw = JsonPrimitive("Tests pass."), date = at(9))
        val usage = UsageStatistics(id = "u-1", promptTokens = 1_000, completionTokens = 14, date = at(10))
        val stop = StopReason(id = "s-1", reason = "end_turn", date = at(10))

        /** The run as the stream shows it: every frame names the live run. */
        val liveTurn: List<LettaMessage> =
            listOf(thought, call, callReturn, narration, secondCall, secondReturn, reply).map { it.withRun(LIVE_RUN) }

        /** The run as App Server message.list stores it: no run id; the prompt owns the run. */
        val durableTurn: List<LettaMessage> =
            listOf(prompt, thought, call, callReturn, narration, secondCall, secondReturn, reply, usage, stop)

        /** Ledger order: time, then identity (TimelinePageKey); tool returns fold into their call. */
        fun List<LettaMessage>.ledgerRows(): List<String> =
            filterNot { it is ToolReturnMessage }.sortedWith(compareBy({ Instant.parse(it.date) }, { it.id })).map { it.id }

        /** Ledger rows of the turn, oldest first. */
        val turnRows: List<String> = durableTurn.ledgerRows()

        fun LettaMessage.withRun(run: String): LettaMessage = when (this) {
            is ReasoningMessage -> copy(runId = run)
            is ToolCallMessage -> copy(runId = run)
            is ToolReturnMessage -> copy(runId = run)
            is AssistantMessage -> copy(runId = run)
            is UsageStatistics -> copy(runId = run)
            is StopReason -> copy(runId = run)
            else -> this
        }

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
