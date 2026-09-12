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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
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

            assertEquals(1, transport.calls)
            assertEquals(3, store.rows.size, "history page was written to the ledger")
            assertEquals(1L, session.publication.value.durableRevision)
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

    private class RecordingPresenter<T : Any> : PagingDataPresenter<T>(Dispatchers.Default, null) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) = Unit

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
        @Volatile var calls = 0
        override suspend fun listConversationMessagePage(
            request: TimelineRemotePageRequest,
            progress: TimelinePageProgress?,
        ): TimelineRemotePageResult {
            calls++
            val page = (records - 1 downTo 0).map { index ->
                TimelineRemoteRecord(
                    TimelineMessageId("m-$index"),
                    AssistantMessage(
                        id = "m-$index", contentRaw = JsonPrimitive("reply $index"),
                        date = "2026-01-01T00:00:0${index}Z",
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
    }
}
