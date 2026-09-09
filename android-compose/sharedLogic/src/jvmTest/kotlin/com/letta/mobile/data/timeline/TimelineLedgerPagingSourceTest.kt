package com.letta.mobile.data.timeline

import androidx.paging.PagingSource
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TimelineLedgerPagingSourceTest {
    @Test fun newestFirstAndExclusiveAppendUseTypedKeys() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineCanonicalWriter { _, _ -> false }, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(TimelineScope("b", "c"))).selection
        val source = TimelineLedgerPagingSource(engine, selection)
        val result = assertIs<PagingSource.LoadResult.Page<TimelinePageKey, TimelineSettledRecord>>(
            source.load(PagingSource.LoadParams.Refresh(null, 64, false)),
        )
        assertEquals(listOf(2L, 1L), result.data.map { it.key.order })
        assertEquals(key(1), result.nextKey)
        source.load(PagingSource.LoadParams.Append(key(1), 64, false))
        assertEquals(TimelineReadPosition.Before(key(1)), store.position)
        engine.release(selection)
        assertIs<PagingSource.LoadResult.Invalid<TimelinePageKey, TimelineSettledRecord>>(
            source.load(PagingSource.LoadParams.Refresh(null, 64, false)),
        )
    }

    @Test fun mediatorOnlyAppendsAndUsesDurableHasMore() = verifyMediator(noProgress = false)

    @Test fun mediatorNoProgressIsAnErrorUntilDurableExhaustion() = verifyMediator(noProgress = true)

    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    private fun verifyMediator(noProgress: Boolean) = runTest {
        val store = Store()
        store.hasMore = true
        var calls = 0
        val transport = object : TimelineTransport {
            override suspend fun sendConversationMessage(
                conversationId: String,
                request: MessageCreateRequest,
            ): Flow<LettaMessage> = emptyFlow()

            override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()

            override suspend fun listConversationMessages(
                conversationId: String,
                limit: Int?,
                after: String?,
                order: String?,
            ): List<LettaMessage> = emptyList()

            override suspend fun listAgentMessages(
                agentId: String,
                limit: Int?,
                order: String?,
                conversationId: String?,
            ): List<LettaMessage> = emptyList()

            override suspend fun listConversationMessagePage(
                request: TimelineRemotePageRequest,
                progress: TimelinePageProgress?,
            ): TimelineRemotePageResult =
                (if (noProgress) {
                    TimelineRemotePageResult.NoProgress(request.requestId, request.selectionGeneration, request.continuation)
                } else {
                    TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration, emptyList(), null, false, 0)
                }).also { calls++ }
        }
        val session = CanonicalTimelineSession(store, transport, TimelineScope("b", "c"), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val mediator = TimelineHistoryMediator(session, selection)
        val emptyState = androidx.paging.PagingState<TimelinePageKey, TimelineSettledRecord>(
            pages = emptyList(),
            anchorPosition = null,
            config = androidx.paging.PagingConfig(pageSize = 64),
            leadingPlaceholderCount = 0,
        )
        val prepend = mediator.load(androidx.paging.LoadType.PREPEND, emptyState)
        val prependSuccess = assertIs<androidx.paging.RemoteMediator.MediatorResult.Success>(prepend)
        kotlin.test.assertTrue(prependSuccess.endOfPaginationReached)
        assertEquals(0, calls)

        if (noProgress) {
            assertIs<androidx.paging.RemoteMediator.MediatorResult.Error>(
                mediator.load(androidx.paging.LoadType.APPEND, emptyState),
            )
            assertEquals(1, calls)
            store.hasMore = false
        }
        val append = mediator.load(androidx.paging.LoadType.APPEND, emptyState)
        assertEquals(1, calls)
        val appendSuccess = assertIs<androidx.paging.RemoteMediator.MediatorResult.Success>(append)
        kotlin.test.assertTrue(appendSuccess.endOfPaginationReached)

        val appendExhausted = mediator.load(androidx.paging.LoadType.APPEND, emptyState)
        assertEquals(1, calls)
        val exhaustedSuccess = assertIs<androidx.paging.RemoteMediator.MediatorResult.Success>(appendExhausted)
        kotlin.test.assertTrue(exhaustedSuccess.endOfPaginationReached)
    }

    @Test fun cancellationEscapesPagingLoad() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineCanonicalWriter { _, _ -> false }, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(TimelineScope("b", "c"))).selection
        store.cancel = true
        assertFailsWith<CancellationException> {
            TimelineLedgerPagingSource(engine, selection).load(PagingSource.LoadParams.Refresh(null, 64, false))
        }
    }

    private class Store : TimelineBoundedStore, TimelineStoreReader, TimelineStoreTransaction {
        var position: TimelineReadPosition? = null
        var hasMore = false
        var cancel = false
        private val tools = mutableMapOf<TimelineScope, TestToolIndexState>()
        override suspend fun putToolCall(entry: TimelineToolIndexEntry): Unit = error("Use scoped transaction")
        override suspend fun setToolSweepGeneration(next: Long): Unit = error("Use scoped transaction")
        override suspend fun toolCall(callId: String): TimelineToolIndexEntry? = error("Use scoped read")
        override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int): List<TimelineToolIndexEntry> = error("Use scoped read")
        override suspend fun toolSweepGeneration(): Long = error("Use scoped read")
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T {
            val snapshot = tools[scope]?.snapshot() ?: TestToolIndexState()
            return block(object : TimelineStoreReader by this {
                override suspend fun toolCall(callId: String) = snapshot.entries[callId]
                override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = snapshot.unresolved(afterCallId, maxRows)
                override suspend fun toolSweepGeneration() = snapshot.generation
            })
        }
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
            val state = tools.getOrPut(scope) { TestToolIndexState() }
            return block(object : TimelineStoreTransaction by this {
                override suspend fun toolCall(callId: String) = state.entries[callId]
                override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = state.unresolved(afterCallId, maxRows)
                override suspend fun toolSweepGeneration() = state.generation
                override suspend fun putToolCall(entry: TimelineToolIndexEntry) { state.entries[entry.callId] = entry }
                override suspend fun setToolSweepGeneration(generation: Long) { state.generation = generation }
            })
        }
        override suspend fun checkpoint() = TimelineDurableCheckpoint(1, null, hasMore)
        override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = null
        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
            if (cancel) throw CancellationException("cancelled")
            this.position = position
            return TimelineMetadataPage(listOf(1L, 2L).map {
                TimelineLedgerMetadata(key(it), TimelineBodyPointer("body-$it", 1), "test", 1)
            }, key(1), null, 1)
        }
        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int) = byteArrayOf(1)
        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = null

        override suspend fun put(record: TimelineStoredRecord) {}
        override suspend fun putEvidence(key: String, value: ByteArray) {}
        override suspend fun deleteEvidence(key: String) {}
        override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) {
            this.hasMore = hasMore
        }
        override suspend fun nextRevision(): Long = 2L
        override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) {}
    }

    companion object {
        private fun key(order: Long) = TimelinePageKey(order, TimelineMessageId("id-$order"))
    }
}
