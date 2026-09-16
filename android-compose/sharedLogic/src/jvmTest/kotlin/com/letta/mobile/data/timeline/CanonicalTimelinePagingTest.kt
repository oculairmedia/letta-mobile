package com.letta.mobile.data.timeline

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

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

    @Test fun settledPresentationDecodesAndProjectsEachRenderedRecordOnce() = runBlocking {
        val store = InMemoryTimelineStore()
        val transport = PageTransport(records = 1)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decodes = AtomicInteger()
        val projections = AtomicInteger()
        val adapter = TimelineSettledProjectionAdapter(
            decode = { record ->
                decodes.incrementAndGet()
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

    private class RecordingPresenter<T : Any> : PagingDataPresenter<T>(Dispatchers.Default, null) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) = Unit

        suspend fun awaitIdle() {
            val idle = withTimeoutOrNull(5_000) {
                loadStateFlow.first { states ->
                    states != null && states.refresh is LoadState.NotLoading &&
                        states.prepend is LoadState.NotLoading && states.append is LoadState.NotLoading
                }
            }
            if (idle == null) fail("Paging did not settle: ${loadStateFlow.value}")
        }

        /** Waits for the row count, and names what the pipeline had done when it did not arrive. */
        suspend fun awaitRows(expected: Int, detail: () -> String) {
            val settled = withTimeoutOrNull(10_000) {
                onPagesUpdatedFlow.first { size == expected }
            }
            if (settled == null) {
                fail("presenter never reached $expected rows: size=$size loadState=${loadStateFlow.value} ${detail()}")
            }
        }
    }

    /** One page of assistant replies, newest first, with no older history behind it. */
    private class PageTransport(private val records: Int) : TimelineTransport {
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
        override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> = error("No send")
        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = error("No stream")
        override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<LettaMessage> = error("No legacy hydration")
        override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?): List<LettaMessage> = error("No legacy hydration")
    }


    companion object {
        private val scope = TimelineScope("backend", "conversation", "agent")

        /** Real-time wait for async pipeline work that has no flow to observe. */
        private suspend fun awaitCondition(detail: () -> String, condition: () -> Boolean) {
            val met = withTimeoutOrNull(10_000) {
                while (!condition()) delay(10)
                true
            }
            if (met == null) fail("condition never held: ${detail()}")
        }
    }
}
