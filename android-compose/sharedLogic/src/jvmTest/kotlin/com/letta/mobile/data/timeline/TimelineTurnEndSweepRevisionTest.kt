package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pixel 9 Pro capture (revision 185): when a turn ended, the tool sweep advanced the durable
 * revision although it had changed no row, so Paging replaced the generation and the settled list
 * redrew once per turn end. Only a sweep that repairs a row a reader can see may publish a revision.
 *
 * Real maintenance, coordinator, engine, canonical writer and Pager over [InMemoryTimelineStore],
 * which refuses a changed transaction that never asked for its revision exactly as Room and the
 * desktop store do. The App Server page is decoded from wire JSON on every reconcile.
 */
class TimelineTurnEndSweepRevisionTest {
    @Test fun aTurnEndWithNoUnresolvedToolPublishesNoRevisionAndReplacesNoGeneration() = runTest {
        val run = turn(settledPage)
        assertTrue(run.beforeTurnEnd.generations >= 1, "the list never loaded: ${run.beforeTurnEnd}")
        assertEquals(run.beforeTurnEnd, run.immediatelyAfterTurnEnd, "turnEnded itself published a revision: $run")
        assertEquals(run.beforeTurnEnd, run.afterRepairSchedule, "the repair schedule published a revision: $run")
        assertEquals(emptyList(), run.failures)
    }

    @Test fun aTurnEndWhoseSweepRepairsADanglingToolStillPublishesOneRevision() = runTest {
        val run = turn(danglingPage)
        assertEquals(run.beforeTurnEnd, run.immediatelyAfterTurnEnd, "the repair must wait for its backoff: $run")
        assertEquals(
            run.beforeTurnEnd.revision + 1, run.afterRepairSchedule.revision,
            "settling a dangling tool must publish exactly one revision: $run",
        )
        assertEquals(run.beforeTurnEnd.generations + 1, run.afterRepairSchedule.generations, "one redraw for the repaired row: $run")
        assertEquals(emptyList<String>(), run.unresolvedAfter, "the dangling call was never settled")
        assertEquals(emptyList(), run.failures)
    }

    private data class Counts(val revision: Long, val generations: Int)

    private data class TurnRun(
        val beforeTurnEnd: Counts,
        val immediatelyAfterTurnEnd: Counts,
        val afterRepairSchedule: Counts,
        val unresolvedAfter: List<String>,
        val failures: List<Throwable>,
    )

    private suspend fun TestScope.turn(page: List<String>): TurnRun {
        val store = InMemoryTimelineStore()
        val transport = StaticPageTransport {
            page.map { decode(it) }.reversed().map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) }
        }
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val failures = mutableListOf<Throwable>()
        val maintenance = IndexedCanonicalTimelineMaintenance(coordinator, this, { _, _, _ -> }, { failures += it })
        val owner = coordinator.acquire(scope)
        val ui = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        val presenter = RecordingPresenter<TimelineSettledRecord>(StandardTestDispatcher(testScheduler))
        val generations = AtomicInteger()
        ui.launch { owner.session.paging(owner.selection).collectLatest { generations.incrementAndGet(); presenter.collectFrom(it) } }
        fun counts() = Counts(owner.session.publication.value.durableRevision, generations.get())

        maintenance.turnStarted(owner, "run-1", "turn-1")
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        advanceUntilIdle()
        val before = counts()
        maintenance.turnEnded(owner, clean = true)
        val immediately = counts()
        advanceUntilIdle()
        val after = counts()
        val unresolved = store.read(scope) { unresolvedTools(null, 64).map { it.callId } }
        ui.cancel()
        return TurnRun(before, immediately, after, unresolved, failures)
    }

    private companion object {
        val scope = TimelineScope("backend", "conv-turn-end", "agent")
        val json = Json { ignoreUnknownKeys = true }

        fun decode(row: String): LettaMessage = json.decodeFromString(LettaMessage.serializer(), row)

        fun row(id: String, type: String, second: Int, fields: String) =
            """{"id":"$id","message_type":"$type","date":"2026-11-20T10:00:%02d.000Z",$fields}""".format(second)

        val prompt = row("msg-prompt", "user_message", 1, """"content":"check it","otid":"cm-prompt"""")
        val call = row("msg-call", "tool_call_message", 2,
            """"tool_call":{"tool_call_id":"call-bash","name":"Bash","arguments":"{\"command\":\"make\"}"}""")
        val callReturn = row("msg-return", "tool_return_message", 3,
            """"tool_call_id":"call-bash","status":"success","tool_return":"ok"""")
        val reply = row("msg-reply", "assistant_message", 9, """"content":"done"""")

        /** Every tool call has its return: the sweep has nothing to repair. */
        val settledPage = listOf(prompt, call, callReturn, reply)

        /** The call never got a return: the sweep must settle it. */
        val danglingPage = listOf(prompt, call, reply)
    }
}
