package com.letta.mobile.data.timeline

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deterministic overlap between the paging mediator's recent-tail reconcile and an explicit
 * [CanonicalTimelineSession.reconcileRecent] for the same selection. The loser answers
 * [TimelineEnginePageOutcome.Stale]; the mediator must not leave prepend in a sticky error.
 */
class TimelineHistoryMediatorStaleRecentRaceTest {
    @OptIn(ExperimentalPagingApi::class)
    @Test fun prependRetriesWhenConcurrentReconcileSupersedesInFlightRequest() = runTest {
        val blockedInTransport = CompletableDeferred<Unit>()
        val releaseFirstTransportCall = CompletableDeferred<Unit>()
        var transportCalls = 0
        val assistant = message("m-1")
        val transport = gatedRecentTransport(
            blockedInTransport,
            releaseFirstTransportCall,
            onCall = { transportCalls++ },
            records = listOf(assistant),
        )
        val session = CanonicalTimelineSession(InMemoryTimelineStore(), transport, TimelineScope("b", "c"), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val mediator = TimelineHistoryMediator(session, selection)

        val prepend = async { mediator.load(LoadType.PREPEND, emptyPagingState()) }
        blockedInTransport.await()
        val overlapping = async { session.reconcileRecent(selection) }
        releaseFirstTransportCall.complete(Unit)

        assertEquals(TimelineEnginePageOutcome.Applied, overlapping.await())
        val result = assertIs<RemoteMediator.MediatorResult.Success>(prepend.await())
        assertTrue(transportCalls >= 3, "mediator must retry after a superseded reconcile: calls=$transportCalls")
        assertTrue(result.endOfPaginationReached)
    }

    @OptIn(ExperimentalPagingApi::class)
    @Test fun prependOnRetiredSelectionDoesNotSurfaceStickyError() = runTest {
        var transportCalls = 0
        val transport = gatedRecentTransport(
            alreadyCompleted(),
            alreadyCompleted(),
            onCall = { transportCalls++ },
            records = emptyList(),
        )
        val session = CanonicalTimelineSession(InMemoryTimelineStore(), transport, TimelineScope("b", "c"), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val mediator = TimelineHistoryMediator(session, selection)
        session.engine.release(selection)

        val result = assertIs<RemoteMediator.MediatorResult.Success>(
            mediator.load(LoadType.PREPEND, emptyPagingState()),
        )
        assertEquals(0, transportCalls, "retired selection must not start another reconcile")
        assertTrue(result.endOfPaginationReached)
    }

    private fun gatedRecentTransport(
        blockedInTransport: CompletableDeferred<Unit>,
        releaseFirst: CompletableDeferred<Unit>,
        onCall: () -> Unit,
        records: List<LettaMessage>,
    ): TimelineTransport = object : TimelineTransport {
        private var calls = 0

        override suspend fun listConversationMessagePage(
            request: TimelineRemotePageRequest,
            progress: TimelinePageProgress?,
        ): TimelineRemotePageResult {
            onCall()
            val ordinal = ++calls
            if (ordinal == 1) {
                blockedInTransport.complete(Unit)
                releaseFirst.await()
            }
            return TimelineRemotePageResult.Page(
                request.requestId,
                request.selectionGeneration,
                records.map { TimelineRemoteRecord(TimelineMessageId(it.id.orEmpty()), it, 0) },
                null,
                false,
                0,
            )
        }

        override suspend fun sendConversationMessage(conversationId: ConversationId, request: MessageCreateRequest): Flow<LettaMessage> =
            emptyFlow()

        override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> = emptyFlow()

        override suspend fun listConversationMessages(
            conversationId: ConversationId,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> = emptyList()

        override suspend fun listAgentMessages(
            agentId: AgentId,
            limit: Int?,
            order: String?,
            conversationId: ConversationId?,
        ): List<LettaMessage> = emptyList()
    }

    private fun message(id: String) = AssistantMessage(
        id = id,
        contentRaw = kotlinx.serialization.json.JsonPrimitive("reply"),
        date = "2026-01-01T00:00:00Z",
    )

    private fun alreadyCompleted(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

    private fun emptyPagingState() = PagingState<TimelinePageKey, TimelineSettledRecord>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = 64),
        leadingPlaceholderCount = 0,
    )
}
