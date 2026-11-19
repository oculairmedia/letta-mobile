package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The "dead zone" flicker (p9 logcat, 2026-11-19): on an idle, unchanged conversation the list
 * redrew every ~1.2 s, each `generation.replaced` right after a `message.list limit=64 order=desc`.
 *
 * Nothing was streaming and the viewport was not the trigger: the newest-64 window held a tool call
 * whose command printed only whitespace. The recent-tail read is the PREPEND walk, and it ends only
 * when a page appends nothing. Replaying that page rewrote the call (its blank stored return was
 * dropped by the merge), then the return put it back and counted as appended, so every read bumped
 * the revision, invalidated the source, and Paging asked PREPEND again, forever.
 *
 * Real Pager, mediator, engine and canonical writer over [InMemoryTimelineStore]; the transport
 * answers the same wire rows each call, decoded afresh as the App Server transport does, on the
 * ~1.2 s round trip the log shows.
 */
class TimelineIdlePrependSettlesTest {
    @Test fun anIdleUnchangedTailWithABlankToolReturnStopsReplacingTheGeneration() = runTest {
        val run = idleRun(unchangedPage)
        assertTrue(run.settled.generations >= 1, "the list never loaded: ${run.settled}")
        assertEquals(run.settled, run.afterIdle, "an idle, unchanged conversation kept replacing its generation: $run")
    }

    @Test fun anIdleTailOfOrdinaryRowsStaysQuietToo() = runTest {
        val run = idleRun(ordinaryPage)
        assertEquals(run.settled, run.afterIdle, "control: ordinary rows must also settle: $run")
    }

    /** Fixing the loop must not cut the prepend that carries another client's message in. */
    @Test fun aRowAnotherClientAddsStillReachesThePresenter() = runTest {
        val tail = mutableListOf<String>().apply { addAll(unchangedPage) }
        val ui = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        val session = session(tail)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val presenter = RecordingPresenter<TimelineSettledRecord>(StandardTestDispatcher(testScheduler))
        ui.launch { session.paging(selection).collectLatest { presenter.collectFrom(it) } }
        advanceTimeBy(IDLE_MS); runCurrent()
        val before = presenter.size

        tail += otherClientReply
        advanceTimeBy(IDLE_MS); runCurrent()
        // Prepend is requested by Paging, not by a timer: a fresh recent read is what an open or a
        // post-send reconcile does, and the new row must land in the list.
        assertEquals(TimelineEnginePageOutcome.Applied, session.reconcileRecent(selection))
        advanceTimeBy(IDLE_MS); runCurrent()
        assertEquals(before + 1, presenter.size, "the other client's message never reached the list")
        ui.cancel()
    }

    private data class Counts(val generations: Int, val calls: Int, val revision: Long)

    private data class IdleRun(val settled: Counts, val afterIdle: Counts)

    private suspend fun TestScope.idleRun(page: List<String>): IdleRun {
        val calls = AtomicInteger()
        val session = session(page.toMutableList(), calls)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val ui = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        val presenter = RecordingPresenter<TimelineSettledRecord>(StandardTestDispatcher(testScheduler))
        val generations = AtomicInteger()
        ui.launch { session.paging(selection).collectLatest { generations.incrementAndGet(); presenter.collectFrom(it) } }
        fun counts() = Counts(generations.get(), calls.get(), session.publication.value.durableRevision)
        advanceTimeBy(SETTLE_MS); runCurrent()
        val settled = counts()
        advanceTimeBy(IDLE_MS); runCurrent()
        return IdleRun(settled, counts()).also { ui.cancel() }
    }

    private fun session(page: MutableList<String>, calls: AtomicInteger = AtomicInteger()): CanonicalTimelineSession {
        val transport = object : TimelineTransport by unexpectedTimelineTransport() {
            override suspend fun listConversationMessagePage(
                request: TimelineRemotePageRequest,
                progress: TimelinePageProgress?,
            ): TimelineRemotePageResult {
                calls.incrementAndGet()
                delay(ROUND_TRIP_MS)
                val messages = page.map(::decode)
                return TimelineRemotePageAdapter.fromMessages(request, messages.reversed(), hasMore = false)
            }
        }
        return CanonicalTimelineSession(InMemoryTimelineStore(), transport, TimelineScope("backend", "conv-idle", "agent"), enabled = true)
    }

    private companion object {
        const val ROUND_TRIP_MS = 1_200L
        const val SETTLE_MS = 20_000L
        const val IDLE_MS = 30_000L
        const val CALL_ID = "toolu-whitespace"
        val json = Json { ignoreUnknownKeys = true }

        fun decode(row: String): LettaMessage = json.decodeFromString(LettaMessage.serializer(), row)

        fun row(id: String, type: String, second: Int, fields: String) =
            """{"id":"$id","message_type":"$type","date":"2026-11-19T20:54:%02d.000Z",$fields}""".format(second)

        val prompt = row("msg-prompt", "user_message", 1, """"content":"check it","otid":"cm-prompt"""")
        val call = row("msg-call:tool:$CALL_ID:request", "approval_request_message", 2,
            """"tool_call":{"tool_call_id":"$CALL_ID","name":"Bash","arguments":"{\"command\":\"true\"}"}""")
        val reply = row("msg-reply", "assistant_message", 9, """"content":"done"""")
        val otherClientReply = row("msg-other-client", "assistant_message", 30, """"content":"from another client"""")

        /** A command that printed only whitespace: its return is "\n\n", stored but blank. */
        val blankReturn = row("msg-return", "tool_return_message", 3,
            """"tool_call_id":"$CALL_ID","status":"success","tool_return":"\n\n"""")
        val textReturn = row("msg-return", "tool_return_message", 3,
            """"tool_call_id":"$CALL_ID","status":"success","tool_return":"ok"""")

        val unchangedPage = listOf(prompt, call, blankReturn, reply)
        val ordinaryPage = listOf(prompt, call, textReturn, reply)
    }
}
