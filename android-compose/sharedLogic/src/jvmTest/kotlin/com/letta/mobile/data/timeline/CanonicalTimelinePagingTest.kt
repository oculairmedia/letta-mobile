package com.letta.mobile.data.timeline

import androidx.paging.LoadState
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives a real [androidx.paging.Pager] end to end: empty ledger, mediator fetches history, the
 * durable revision bumps, and the presenter must end up holding the rows. The source and mediator
 * tests exercise each half in isolation; this is the seam between them that the screen depends on.
 *
 * Runs on real dispatchers and real time. Paging's fetcher, the mediator and the presenter each
 * hop contexts, and driving that through a virtual-time scheduler proves nothing about the seam.
 */
class CanonicalTimelinePagingTest {
    @Test fun appliedHistoryPageReachesThePresenter() = runBlocking {
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 3)
        val session = CanonicalTimelineSession(store, transport, scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val presenter = RecordingPresenter<TimelineSettledRecord>()
        val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            collectors.launch { session.paging(selection).collectLatest { presenter.collectFrom(it) } }
            presenter.awaitRows(3) { "calls=${transport.calls} ledgerRows=${store.rows.size} revision=${session.publication.value.durableRevision}" }

            // The revision-triggered newest check starts after the presenter first goes idle, so
            // wait for the pipeline to finish it instead of snapshotting a still-running walk.
            awaitCondition({ "calls=${transport.calls} revision=${session.publication.value.durableRevision}" }) {
                transport.calls >= 3 && session.publication.value.durableRevision >= 2L
            }
            presenter.awaitIdle()
            // Initial newest read, older-cursor walk, then the revision-triggered newest check.
            assertEquals(3, transport.calls)
            assertEquals(3, store.rows.size, "history page was written to the ledger")
            // Recent reconciliation stores rows; the independent older walk then persists exhaustion.
            assertEquals(2L, session.publication.value.durableRevision)
            assertEquals(false, store.current.hasMore)
            assertEquals(null, store.current.continuation)
            assertIs<LoadState.NotLoading>(presenter.loadStateFlow.value?.refresh)
            assertEquals(listOf("m-2", "m-1", "m-0"), presenter.snapshot().items.map { it.key.identity.value })
        } finally {
            collectors.cancel()
        }
    }

    @Test fun presentationSettledRowsReachThePresenter() = runBlocking {
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 3)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()
        try {
            ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
            presenter.awaitRows(3) { "calls=${transport.calls} ledgerRows=${store.rows.size}" }

            assertEquals(3, store.rows.size, "history page was written to the ledger")
            assertIs<LoadState.NotLoading>(presenter.loadStateFlow.value?.refresh)
            presentation.close()
        } finally {
            ui.cancel()
        }
    }

    @Test fun pagingSourcePreparesThirtyTwoRowsInOneStoreSnapshot() = runBlocking {
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

    @Test fun consumingPreparedPageDoesNotReadSuppressionPerRow() = runBlocking {
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 32)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        assertEquals(TimelineEnginePageOutcome.Applied, owner.session.loadOlder(owner.selection))
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()
        val readsBeforeCollection = store.reads
        try {
            ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
            presenter.awaitRows(32) { "reads=${store.reads - readsBeforeCollection}" }
            presenter.awaitIdle()
            // Includes Paging's boundary checkpoint probes, not just the single page snapshot.
            kotlin.test.assertTrue(store.reads - readsBeforeCollection <= 4,
                "UI consumption must not add 32 suppression reads: ${store.reads - readsBeforeCollection}")
            // The newest end is an independent walk and still reconciles once on open, so the local
            // page is not re-fetched as history: the ledger keeps exactly the rows already prepared.
            assertEquals(32, store.rows.size, "consuming the prepared page must not refetch it as history")
            kotlin.test.assertTrue(transport.calls <= 2,
                "only the one history page plus the bounded newest reconcile: ${transport.calls}")
        } finally {
            presentation.close()
            ui.cancel()
        }
    }

    @Test fun repeatedDurableRevisionsKeepOnePagerAndReplaceOnlyItsSource() = runBlocking {
        val store = InMemoryTimelineStore()
        val session = CanonicalTimelineSession(store, PageTransport(records = 1), scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val pagingData = mutableListOf<androidx.paging.PagingData<TimelineSettledRecord>>()
        val presenter = RecordingPresenter<TimelineSettledRecord>()
        val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Every durable revision after this may invalidate the source once: the five tool sweeps
        // below, and any the newest-end mediator applies on its own, depending on timing.
        val revisionAtSubscribe = session.publication.value.durableRevision
        try {
            collectors.launch {
                session.paging(selection).collect { page ->
                    pagingData += page
                    presenter.collectFrom(page)
                }
            }
            presenter.awaitRows(1) { "initial page never arrived" }
            val initialRevision = session.publication.value.durableRevision
            repeat(5) { cycle ->
                session.engine.advanceToolSweep(selection)
                awaitCondition({ "cycle=$cycle revision=${session.publication.value.durableRevision}" }) {
                    session.publication.value.durableRevision >= initialRevision + cycle + 1L
                }
                presenter.awaitIdle()
                assertEquals(listOf("m-0"), presenter.snapshot().items.map { it.key.identity.value })
            }

            assertTrue(pagingData.size > 1, "durable revisions must replace invalidated sources")
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
        } finally {
            collectors.cancel()
        }
    }

    @Test fun settledPresentationDecodesAndProjectsEachRenderedRecordOnce() = runBlocking {
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 1)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
        val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()
        try {
            ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
            presenter.awaitRows(1) { "calls=${transport.calls} ledgerRows=${store.rows.size}" }
            presenter.awaitIdle()
            // The newest and older walks fetch independently, so the second walk's commit can bump
            // the durable revision after the first page is on screen, and its reload decodes again.
            // Counting before that reload raced it (CI on #1672: 1, then 2 after the delay). Wait
            // until history is exhausted and the last decode rendered the ledger's final revision;
            // nothing can invalidate the source after that.
            awaitCondition({ "presenter never rendered the final ledger: calls=${transport.calls}" }) {
                !store.current.hasMore &&
                    decodedAtRevision.get() == owner.session.publication.value.durableRevision
            }
            presenter.awaitIdle()

            // Paging can emit a second generation after the first page lands (revision
            // bump / refresh). collectLatest then rebuilds the presenter, so the
            // production path decodes/projects that same record again. The gate is
            // "once per paging generation, not a per-row loop", not "exactly one
            // generation for the whole open".
            val decodeCount = decodes.get()
            val projectCount = projections.get()
            kotlin.test.assertTrue(
                decodeCount in 1..2,
                "settled production decodes each rendered record once per paging generation: $decodeCount",
            )
            kotlin.test.assertTrue(
                projectCount in 1..2,
                "settled production projects each rendered record once per paging generation: $projectCount",
            )
            assertEquals(decodeCount, projectCount, "decode and project stay paired")
            assertEquals("otid-m-0", presenter.snapshot().items.single().otid)
            delay(50)
            assertEquals(decodeCount, decodes.get(), "a settled presenter must not keep re-decoding")
            presentation.close()
        } finally {
            ui.cancel()
        }
    }

    @Test fun reasoningHandoffHasOneOrderedRowBeforeAndAfterSettlementAndReopen() = runBlocking {
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
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val fence = coordinator.beginLive(owner)
        val liveThought = thought.copy(id = "cm-stream-thought", runId = "local-run-1967")
        val liveReply = reply.copy(id = "cm-stream-reply", runId = "local-run-1967")
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(liveThought)))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(liveReply)))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()
        try {
            ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
            presenter.awaitRows(2) { "settled rows never arrived" }
            presenter.awaitIdle()
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
            awaitLiveDrained(owner, presentation)
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
            val reopenedRows = RecordingPresenter<CanonicalTimelinePresentation.Row>()
            ui.launch { reopenedPresentation.settled.collectLatest { reopenedRows.collectFrom(it) } }
            reopenedRows.awaitRows(2) { "reopened settled rows never arrived" }
            reopenedRows.awaitIdle()
            assertEquals(
                listOf(
                    "segment-server-reply" to "assistant:It is current",
                    "segment-server-thought" to "reasoning:Inspect the state",
                ),
                reopenedRows.snapshot().items.map { it.item.key to renderSignature(it.item) },
            )
            reopenedPresentation.close()
            presentation.close()
        } finally {
            ui.cancel()
        }
    }

    @Test fun resolvedStreamedKeyIsPreservedAcrossPostSettlementPagingRevisions() = runBlocking {
        val streamedId = "cm-stream-m-0"
        val canonicalId = "m-0"
        val store = InMemoryTimelineStore()
        store.putEvidence("identity/serverId/$streamedId", canonicalId.encodeToByteArray())
        val transport = PageTransport(records = 1)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val fence = coordinator.beginLive(owner)
        val streamedMsg = AssistantMessage(
            id = streamedId,
            contentRaw = JsonPrimitive("streamed content"),
            date = "2026-01-01T00:00:00Z",
        )
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(streamedMsg)))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        val liveKey = awaitLiveKey(presentation)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()
        val emittedGenerations = AtomicInteger(0)
        try {
            ui.launch {
                presentation.settled.collectLatest { page ->
                    emittedGenerations.incrementAndGet()
                    presenter.collectFrom(page)
                }
            }
            presenter.awaitRows(1) { "initial page never arrived" }
            presenter.awaitIdle()
            val initialRow = presenter.snapshot().items.single()
            assertEquals(TimelineMessageId(canonicalId), initialRow.identity)
            // The settled row takes over the live row's slot (letta-mobile-sibr8).
            assertEquals(liveKey, initialRow.item.key)

            // Acknowledge settlement: this clears owner.session.live
            presentation.onResidentRows(listOf(initialRow))
            awaitCondition({ "live did not clear on settlement" }) { owner.session.live.value == null }

            val genBeforeSweep = emittedGenerations.get()
            // Advance a durable revision to invalidate Paging and force a new paging generation
            owner.session.engine.advanceToolSweep(owner.selection)
            awaitCondition({ "new paging generation did not arrive" }) {
                emittedGenerations.get() > genBeforeSweep
            }
            presenter.awaitIdle()

            // Post-settlement paging generation must retain the streamed key rather than reverting to canonical identity
            val postSettlementRow = presenter.snapshot().items.single()
            assertEquals(TimelineMessageId(canonicalId), postSettlementRow.identity)
            assertEquals(liveKey, postSettlementRow.item.key)
            presentation.close()
        } finally {
            ui.cancel()
        }
    }

    /**
     * Crash 2026-09-24 20:49:40 (`Key "segment-ui-msg-9173264" was already used`). The previous
     * turn's repair never committed, so this turn's repair page appended BOTH turns' replies. The
     * stream and the 0.32.17 ledger share the ui-msg id, but the overlay's text was short (its
     * tail was dropped), so content could not pair them and the positional fallback paired the
     * streamed reply with the OLDER turn's row. That row inherited `segment-ui-msg-9173264` while
     * the true row kept the same key under its own identity.
     */
    @Test fun settledOverlayNeverLendsItsKeyToAnEarlierTurnsRow() = runBlocking {
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
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
        val fence = coordinator.beginLive(owner)
        // The overlay holds the reply under the stream's (and ledger's) id, short of its tail.
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(reply.copy(contentRaw = JsonPrimitive("Hello")))))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        val liveKey = awaitLiveKey(presentation)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        assertEquals(emptyMap(), owner.session.live.value?.aliases, "an exact id match needs no alias")
        val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()
        try {
            ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
            presenter.awaitRows(2) { "settled rows never arrived" }
            presenter.awaitIdle()
            val keys = presenter.snapshot().items.map { it.identity.value to it.item.key }
            assertEquals(
                // The true row takes the live reply's slot; the earlier turn's row keeps its own.
                listOf("ui-msg-9173264" to liveKey, "ui-msg-9173262" to "segment-ui-msg-9173262"),
                keys,
            )
            presentation.close()
        } finally {
            ui.cancel()
        }
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
                        date = "2026-01-01T00:00:0${index}Z", otid = "otid-m-$index",
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
         * The fence releases, then the presentation re-projects its live list on its own dispatcher;
         * reading that list in the same instant as the release raced the hop (CI, #1673).
         */
        private suspend fun awaitLiveDrained(
            owner: CanonicalTimelineCoordinator.Owner,
            presentation: CanonicalTimelinePresentation,
        ) = awaitCondition({ "live still ${presentation.live.value}" }) {
            owner.session.live.value == null && presentation.live.value.isEmpty()
        }

        /** The key the single live row renders under before the turn settles. */
        private suspend fun awaitLiveKey(presentation: CanonicalTimelinePresentation): String {
            awaitCondition({ "live row never projected" }) { presentation.live.value.size == 1 }
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
