package com.letta.mobile.data.timeline

import androidx.paging.PagingSource
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
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

    @Test fun mediatorOnlyAppendsAndUsesDurableHasMore() = runTest {
        val store = Store()
        store.hasMore = true
        var calls = 0
        val transport = object : TimelineTransport {
            override suspend fun streamConversation(conversationId: String) = kotlinx.coroutines.flow.emptyFlow<TimelineStreamFrame>()
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest): TimelineRemotePageResult =
                TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration, emptyList(), null, false, 0).also { calls++ }
        }
        val session = CanonicalTimelineSession(store, transport, TimelineScope("b", "c"), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        val mediator = TimelineHistoryMediator(session, selection)
        assertEquals(androidx.paging.RemoteMediator.MediatorResult.Success::class,
            (mediator.load(androidx.paging.LoadType.PREPEND, androidx.paging.PagingState(emptyList(), null, androidx.paging.PagingSource.LoadResult.Invalid(), null)) as androidx.paging.RemoteMediator.MediatorResult.Success).let { 1 })
        store.hasMore = false
        val result = mediator.load(androidx.paging.LoadType.APPEND, androidx.paging.PagingState(emptyList(), null, androidx.paging.PagingSource.LoadResult.Invalid(), null))
        assertEquals(1, calls)
        kotlin.test.assertIs<androidx.paging.RemoteMediator.MediatorResult.Success>(result)
        kotlin.test.assertTrue((result as androidx.paging.RemoteMediator.MediatorResult.Success).endOfPaginationReached)
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

    private class Store : TimelineBoundedStore, TimelineStoreReader {
        var position: TimelineReadPosition? = null
        var hasMore = false
        var cancel = false
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = block(this)
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T = error("read only")
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
    }

    companion object {
        private fun key(order: Long) = TimelinePageKey(order, TimelineMessageId("id-$order"))
    }
}
