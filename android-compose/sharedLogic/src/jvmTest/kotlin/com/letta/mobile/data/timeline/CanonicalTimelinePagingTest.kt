package com.letta.mobile.data.timeline

import androidx.paging.LoadState
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives a real [androidx.paging.Pager] end to end: empty ledger, mediator fetches history, the
 * durable revision bumps, and the presenter must end up holding the rows. The source and mediator
 * tests exercise each half in isolation; this is the seam between them that the screen depends on.
 *
 * Runs the real Pager, mediator, sources and presenter on one virtual-time scheduler: the collectors
 * live in a foreground scope of the test (see [runUiTest]) and the presenter presents on a
 * [StandardTestDispatcher] of the same scheduler, so `advanceUntilIdle` runs the whole pipeline dry
 * before anything is asserted. On
 * real dispatchers these tests polled for conditions and raced Paging's own hops: CI saw an extra
 * generation for one revision and a newest-end check that never ran (letta-mobile-vyi8l).
 */
class CanonicalTimelinePagingTest {
    @Test fun appliedHistoryPageReachesThePresenter() = runUiTest { ui ->
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 3)
        val session = CanonicalTimelineSession(store, transport, scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val presenter = presenter<TimelineSettledRecord>()
        ui.launch { session.paging(selection).collectLatest { presenter.collectFrom(it) } }
        advanceUntilIdle()

        val detail = { "calls=${transport.calls} ledgerRows=${store.rows.size} revision=${session.publication.value.durableRevision}" }
        presenter.assertIdle(detail)
        assertEquals(3, presenter.size, detail())
        // Initial newest read, older-cursor walk, then the revision-triggered newest check.
        assertEquals(3, transport.calls, detail())
        assertEquals(3, store.rows.size, "history page was written to the ledger")
        // Recent reconciliation stores rows; the independent older walk then persists exhaustion.
        assertEquals(2L, session.publication.value.durableRevision)
        assertEquals(false, store.current.hasMore)
        assertEquals(null, store.current.continuation)
        assertIs<LoadState.NotLoading>(presenter.loadStateFlow.value?.refresh)
        assertEquals(listOf("m-2", "m-1", "m-0"), presenter.snapshot().items.map { it.key.identity.value })
    }

    @Test fun presentationSettledRowsReachThePresenter() = runUiTest { ui ->
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 3)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val presenter = presenter<CanonicalTimelinePresentation.Row>()
        ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
        advanceUntilIdle()

        assertEquals(3, presenter.size, "calls=${transport.calls} ledgerRows=${store.rows.size}")
        assertEquals(3, store.rows.size, "history page was written to the ledger")
        assertIs<LoadState.NotLoading>(presenter.loadStateFlow.value?.refresh)
        presentation.close()
    }

    @Test fun pagingSourcePreparesThirtyTwoRowsInOneStoreSnapshot() = runTest {
        val store = InMemoryTimelineStore()
        val session = CanonicalTimelineSession(store, PageTransport(records = 32), scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        assertEquals(TimelineEnginePageOutcome.Applied, session.loadOlder(selection))
        val readsBeforeLoad = store.reads

        val page = assertIs<androidx.paging.PagingSource.LoadResult.Page<TimelinePageKey, TimelineSettledRecord>>(
            TimelineLedgerPagingSource(session.engine, selection).load(
                androidx.paging.PagingSource.LoadParams.Refresh(null, 32, false),
            ),
        )

        assertEquals(32, page.data.size)
        page.data.forEach { assertIs<TimelineSettledPresentation.Render>(it.preparedPresentation) }
        assertEquals(1, store.reads - readsBeforeLoad, "page preparation must not re-enter the store per row")
    }

    @Test fun consumingPreparedPageDoesNotReadSuppressionPerRow() = runUiTest { ui ->
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 32)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        assertEquals(TimelineEnginePageOutcome.Applied, owner.session.loadOlder(owner.selection))
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val presenter = presenter<CanonicalTimelinePresentation.Row>()
        val readsBeforeCollection = store.reads
        ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
        advanceUntilIdle()

        assertEquals(32, presenter.size, "reads=${store.reads - readsBeforeCollection}")
        presenter.assertIdle()
        // Includes Paging's boundary checkpoint probes, not just the single page snapshot.
        assertTrue(store.reads - readsBeforeCollection <= 4,
            "UI consumption must not add 32 suppression reads: ${store.reads - readsBeforeCollection}")
        // The newest end is an independent walk and still reconciles once on open, so the local
        // page is not re-fetched as history: the ledger keeps exactly the rows already prepared.
        assertEquals(32, store.rows.size, "consuming the prepared page must not refetch it as history")
        assertTrue(transport.calls <= 2,
            "only the one history page plus the bounded newest reconcile: ${transport.calls}")
        presentation.close()
    }

    @Test fun repeatedDurableRevisionsKeepOnePagerAndReplaceOnlyItsSource() = runUiTest { ui ->
        val store = InMemoryTimelineStore()
        val session = CanonicalTimelineSession(store, PageTransport(records = 1), scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val pagingData = mutableListOf<androidx.paging.PagingData<TimelineSettledRecord>>()
        val presenter = presenter<TimelineSettledRecord>()
        // Every durable revision after this may invalidate the source once: the five tool sweeps
        // below, and the ones the mediator's newest and older walks apply on open.
        val revisionAtSubscribe = session.publication.value.durableRevision
        ui.launch {
            session.paging(selection).collect { page ->
                pagingData += page
                presenter.collectFrom(page)
            }
        }
        advanceUntilIdle()
        assertEquals(1, presenter.size, "initial page never arrived")
        val initialRevision = session.publication.value.durableRevision
        val generationsBeforeSweeps = pagingData.size
        repeat(5) { cycle ->
            session.engine.advanceToolSweep(selection)
            advanceUntilIdle()
            assertEquals(initialRevision + cycle + 1L, session.publication.value.durableRevision, "cycle=$cycle")
            presenter.assertIdle { "cycle=$cycle" }
            assertEquals(listOf("m-0"), presenter.snapshot().items.map { it.key.identity.value })
        }

        // Each sweep was settled on its own, so each replaced the one source exactly once.
        assertEquals(generationsBeforeSweeps + 5, pagingData.size, "one generation per tool sweep")
        // One Pager, at most one generation per durable revision: the initial page plus one per
        // revision since subscribing. A rebuilt Pager, or an invalidation per emission, exceeds it.
        val revisions = session.publication.value.durableRevision - revisionAtSubscribe
        assertTrue(revisions >= 5, "the five tool sweeps each published a revision: $revisions")
        assertTrue(
            pagingData.size <= 1 + revisions,
            "one Pager must coalesce invalidations to one generation per durable revision: " +
                "${pagingData.size} generations for $revisions revisions",
        )
        assertTrue(store.reads > 1, "source refreshes must read new ledger snapshots")
    }

    /**
     * The interleaving behind CI's "9 generations for 7 revisions" (letta-mobile-vyi8l), replayed
     * step by step: a source loads after a revision lands, finds itself obsolete and is replaced by
     * Paging with one built at that revision; only then does the revision collector reach the same
     * revision. It must leave the replacement alone. Before the fix it invalidated it again: two
     * generations, and two redraws, for one revision.
     */
    @Test fun aSourceAlreadyReplacedForARevisionIsNotInvalidatedAgain() = runTest {
        val store = InMemoryTimelineStore()
        val session = CanonicalTimelineSession(store, PageTransport(records = 1), scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        assertEquals(TimelineEnginePageOutcome.Applied, session.loadOlder(selection))
        val obsolete = TimelineLedgerPagingSource(session.engine, selection)

        session.engine.advanceToolSweep(selection)
        val revision = session.publication.value.durableRevision
        // Paging replaces a source whose load returns Invalid...
        assertIs<androidx.paging.PagingSource.LoadResult.Invalid<TimelinePageKey, TimelineSettledRecord>>(
            obsolete.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 32, false)),
        )
        // ...with one built at the revision that made it obsolete.
        val replacement = TimelineLedgerPagingSource(session.engine, selection)

        // The collector then reaches that revision.
        assertFalse(replacement.invalidateIfBehind(revision), "the replacement already reads this revision")
        assertFalse(replacement.invalid)
        // A source still behind it is replaced, as before.
        assertTrue(obsolete.invalidateIfBehind(revision))
        assertTrue(obsolete.invalid)
    }

    @Test fun settledPresentationDecodesAndProjectsEachRenderedRecordOnce() = runUiTest { ui ->
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 1)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val decodes = AtomicInteger()
        val projections = AtomicInteger()
        // The durable revision current when the presenter last decoded: it names the ledger
        // generation that reload rendered.
        val decodedAtRevision = java.util.concurrent.atomic.AtomicLong(-1)
        val adapter = TimelineSettledProjectionAdapter(
            decode = { record ->
                decodes.incrementAndGet()
                decodedAtRevision.set(owner.session.publication.value.durableRevision)
                DefaultTimelineSettledProjectionAdapter.decode(record).also {
                    // A second raw decode outside this adapter must fail too, not evade the counter.
                    record.body.fill(0)
                }
            },
            project = { record, event, ownAgentId ->
                projections.incrementAndGet()
                DefaultTimelineSettledProjectionAdapter.project(record, event, ownAgentId)
            },
        )
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui, settledProjectionAdapter = adapter)
        val presenter = presenter<CanonicalTimelinePresentation.Row>()
        ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
        // The newest and older walks fetch independently, so the second walk's commit bumps the
        // durable revision after the first page is on screen, and its reload decodes again. Run the
        // pipeline dry: history is exhausted and the last decode rendered the final revision.
        advanceUntilIdle()
        assertEquals(1, presenter.size, "calls=${transport.calls} ledgerRows=${store.rows.size}")
        presenter.assertIdle()
        assertFalse(store.current.hasMore, "history was not exhausted: calls=${transport.calls}")
        assertEquals(owner.session.publication.value.durableRevision, decodedAtRevision.get(),
            "presenter never rendered the final ledger: calls=${transport.calls}")

        // Paging can emit a second generation after the first page lands (revision
        // bump / refresh). collectLatest then rebuilds the presenter, so the
        // production path decodes/projects that same record again. The gate is
        // "once per paging generation, not a per-row loop", not "exactly one
        // generation for the whole open".
        val decodeCount = decodes.get()
        val projectCount = projections.get()
        assertTrue(
            decodeCount in 1..2,
            "settled production decodes each rendered record once per paging generation: $decodeCount",
        )
        assertTrue(
            projectCount in 1..2,
            "settled production projects each rendered record once per paging generation: $projectCount",
        )
        assertEquals(decodeCount, projectCount, "decode and project stay paired")
        assertEquals("otid-m-0", presenter.snapshot().items.single().otid)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(decodeCount, decodes.get(), "a settled presenter must not keep re-decoding")
        presentation.close()
    }

    @Test fun reasoningHandoffHasOneOrderedRowBeforeAndAfterSettlementAndReopen() = runUiTest { ui ->
        val store = InMemoryTimelineStore()
        val thought = ReasoningMessage(
            id = "server-thought", reasoning = "Inspect the state", date = "2026-01-01T00:00:01Z",
        )
        val reply = AssistantMessage(
            id = "server-reply", contentRaw = JsonPrimitive("It is current"), date = "2026-01-01T00:00:02Z",
        )
        val transport = object : TimelineTransport by PageTransport(0) {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
                TimelineRemotePageResult.Page(
                    request.requestId, request.selectionGeneration,
                    listOf(TimelineRemoteRecord(TimelineMessageId(thought.id), thought, 0), TimelineRemoteRecord(TimelineMessageId(reply.id), reply, 0)),
                    null, false, 0,
                )
        }
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val fence = coordinator.beginLive(owner)
        val liveThought = thought.copy(id = "cm-stream-thought", runId = "local-run-1967")
        val liveReply = reply.copy(id = "cm-stream-reply", runId = "local-run-1967")
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(hostStamped(liveThought))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(hostStamped(liveReply))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        val presenter = presenter<CanonicalTimelinePresentation.Row>()
        ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
        advanceUntilIdle()
        assertEquals(2, presenter.size, "settled rows never arrived")
        presenter.assertIdle()
        val settledRows = presenter.snapshot().items
        val before = settledRows.map { it.item.key to renderSignature(it.item) } +
            presentation.live.value.map { it.key to renderSignature(it) }
        assertEquals(
            listOf(
                "segment-cm-stream-reply" to "assistant:It is current",
                "segment-cm-stream-thought" to "reasoning:Inspect the state",
                "run-local-run-1967" to "run:local-run-1967:Inspect the state|It is current",
            ),
            before,
        )
        presentation.onResidentRows(settledRows)
        // The fence releases, then the presentation re-projects its live list on its own scope.
        advanceUntilIdle()
        assertEquals(null, owner.session.live.value, "live still ${owner.session.live.value}")
        assertEquals(emptyList(), presentation.live.value, "live still ${presentation.live.value}")
        val after = presenter.snapshot().items.map { it.item.key to renderSignature(it.item) }
        assertEquals(
            listOf(
                "segment-cm-stream-reply" to "assistant:It is current",
                "segment-cm-stream-thought" to "reasoning:Inspect the state",
            ),
            after,
        )
        val reopened = coordinator.acquire(scope)
        val reopenedPresentation = CanonicalTimelinePresentation.open(coordinator, reopened, ui)
        val reopenedRows = presenter<CanonicalTimelinePresentation.Row>()
        ui.launch { reopenedPresentation.settled.collectLatest { reopenedRows.collectFrom(it) } }
        advanceUntilIdle()
        assertEquals(2, reopenedRows.size, "reopened settled rows never arrived")
        reopenedRows.assertIdle()
        assertEquals(
            listOf(
                "segment-server-reply" to "assistant:It is current",
                "segment-server-thought" to "reasoning:Inspect the state",
            ),
            reopenedRows.snapshot().items.map { it.item.key to renderSignature(it.item) },
        )
        reopenedPresentation.close()
        presentation.close()
    }

    @Test fun resolvedStreamedKeyIsPreservedAcrossPostSettlementPagingRevisions() = runUiTest { ui ->
        val streamedId = "cm-stream-m-0"
        val canonicalId = "m-0"
        val store = InMemoryTimelineStore()
        store.putEvidence("identity/serverId/$streamedId", canonicalId.encodeToByteArray())
        val transport = PageTransport(records = 1)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val fence = coordinator.beginLive(owner)
        val streamedMsg = AssistantMessage(
            id = streamedId,
            contentRaw = JsonPrimitive("streamed content"),
            date = "2026-01-01T00:00:00Z",
        )
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(hostStamped(streamedMsg))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        val liveKey = liveKey(presentation)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        val presenter = presenter<CanonicalTimelinePresentation.Row>()
        val emittedGenerations = AtomicInteger(0)
        ui.launch {
            presentation.settled.collectLatest { page ->
                emittedGenerations.incrementAndGet()
                presenter.collectFrom(page)
            }
        }
        advanceUntilIdle()
        assertEquals(1, presenter.size, "initial page never arrived")
        presenter.assertIdle()
        val initialRow = presenter.snapshot().items.single()
        assertEquals(TimelineMessageId(canonicalId), initialRow.identity)
        // The settled row takes over the live row's slot (letta-mobile-sibr8).
        assertEquals(liveKey, initialRow.item.key)

        // Acknowledge settlement: this clears owner.session.live
        presentation.onResidentRows(listOf(initialRow))
        advanceUntilIdle()
        assertEquals(null, owner.session.live.value, "live did not clear on settlement")

        val genBeforeSweep = emittedGenerations.get()
        // Advance a durable revision to invalidate Paging and force a new paging generation
        owner.session.engine.advanceToolSweep(owner.selection)
        advanceUntilIdle()
        assertTrue(emittedGenerations.get() > genBeforeSweep, "new paging generation did not arrive")
        presenter.assertIdle()

        // Post-settlement paging generation must retain the streamed key rather than reverting to canonical identity
        val postSettlementRow = presenter.snapshot().items.single()
        assertEquals(TimelineMessageId(canonicalId), postSettlementRow.identity)
        assertEquals(liveKey, postSettlementRow.item.key)
        presentation.close()
    }

    /**
     * Crash 2026-09-24 20:49:40 (`Key "segment-ui-msg-9173264" was already used`). The previous
     * turn's repair never committed, so this turn's repair page appended BOTH turns' replies. The
     * stream and the 0.32.17 ledger share the ui-msg id, but the overlay's text was short (its
     * tail was dropped), so content could not pair them and the positional fallback paired the
     * streamed reply with the OLDER turn's row. That row inherited `segment-ui-msg-9173264` while
     * the true row kept the same key under its own identity.
     */
    @Test fun settledOverlayNeverLendsItsKeyToAnEarlierTurnsRow() = runUiTest { ui ->
        val store = InMemoryTimelineStore()
        val earlier = AssistantMessage(
            id = "ui-msg-9173262", contentRaw = JsonPrimitive("Earlier answer"), date = "2026-01-01T00:00:01Z",
        )
        val reply = AssistantMessage(
            id = "ui-msg-9173264", contentRaw = JsonPrimitive("Hello there, friend"), date = "2026-01-01T00:00:02Z",
        )
        val transport = object : TimelineTransport by PageTransport(0) {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
                TimelineRemotePageResult.Page(
                    request.requestId, request.selectionGeneration,
                    listOf(TimelineRemoteRecord(TimelineMessageId(reply.id), reply, 0),
                        TimelineRemoteRecord(TimelineMessageId(earlier.id), earlier, 0)),
                    null, false, 0,
                )
        }
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val fence = coordinator.beginLive(owner)
        // The overlay holds the reply under the stream's (and ledger's) id, short of its tail.
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(hostStamped(reply.copy(contentRaw = JsonPrimitive("Hello"))))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        val liveKey = liveKey(presentation)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        assertEquals(emptyMap(), owner.session.live.value?.aliases, "an exact id match needs no alias")
        val presenter = presenter<CanonicalTimelinePresentation.Row>()
        ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
        advanceUntilIdle()
        assertEquals(2, presenter.size, "settled rows never arrived")
        presenter.assertIdle()
        val keys = presenter.snapshot().items.map { it.identity.value to it.item.key }
        assertEquals(
            // The true row takes the live reply's slot; the earlier turn's row keeps its own.
            listOf("ui-msg-9173264" to liveKey, "ui-msg-9173262" to "segment-ui-msg-9173262"),
            keys,
        )
        presentation.close()
    }

    /** One page of assistant replies, newest first, with no older history behind it. */
    private class PageTransport(private val records: Int) : TimelineTransport by unexpectedTimelineTransport() {
        // The newest and older walks fetch independently; a plain ++ can lose a concurrent call.
        private val callCount = AtomicInteger()
        val calls: Int get() = callCount.get()
        override suspend fun listConversationMessagePage(
            request: TimelineRemotePageRequest,
            progress: TimelinePageProgress?,
        ): TimelineRemotePageResult {
            callCount.incrementAndGet()
            val page = (records - 1 downTo 0).map { index ->
                TimelineRemoteRecord(
                    TimelineMessageId("m-$index"),
                    AssistantMessage(
                        id = "m-$index", contentRaw = JsonPrimitive("reply $index"),
                        date = "2026-01-01T00:00:0${index}Z", logicalMessageId = "otid-m-$index",
                    ),
                    0,
                )
            }
            return TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration, page, null, false, 0)
        }
    }


    companion object {
        private val scope = TimelineScope("backend", "conversation", "agent")

        /**
         * [runTest] with a [ui] scope on the test scheduler for collectors and presentations. Not
         * [TestScope.backgroundScope]: `advanceUntilIdle` stops once only background work is left,
         * so a pipeline collected there would never run. Cancelled when the test ends.
         */
        private fun runUiTest(body: suspend TestScope.(ui: CoroutineScope) -> Unit) = runTest {
            val ui = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            try {
                body(ui)
            } finally {
                ui.cancel()
            }
        }

        /** A presenter on the test's scheduler, so `advanceUntilIdle` also runs its presentation. */
        private fun <T : Any> TestScope.presenter() = RecordingPresenter<T>(StandardTestDispatcher(testScheduler))

        /** The key the single live row renders under before the turn settles, once it is projected. */
        private fun TestScope.liveKey(presentation: CanonicalTimelinePresentation): String {
            advanceUntilIdle()
            assertEquals(1, presentation.live.value.size, "live row never projected")
            return presentation.live.value.single().key
        }

        private fun renderSignature(item: com.letta.mobile.data.chat.projection.ChatRenderItem): String = when (item) {
            is com.letta.mobile.data.chat.projection.ChatRenderItem.Single ->
                "${if (item.message.isReasoning) "reasoning" else item.message.role}:${item.message.content}"
            is com.letta.mobile.data.chat.projection.ChatRenderItem.RunBlock ->
                "run:${item.runId}:${item.messages.joinToString("|") { it.first.content }}"
        }
    }
}
