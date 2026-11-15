package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shared in-memory store must read pages the way RoomTimelineBoundedStore and the desktop store
 * do, or every store-backed timeline test proves something the product never does.
 *
 * Room's contract: a page's older/newer edge is the page's OWN first/last row, reported only when the
 * ledger holds more beyond it, and Paging hands that key straight to Before/After, which exclude it.
 * Around(key) reads (maxRows - 1) / 2 older rows, the row itself, then newer rows.
 */
class InMemoryTimelineStoreEdgesTest {
    private val scope = TimelineScope("backend", "conversation", "agent")

    private fun key(order: Int) = TimelinePageKey(order.toLong(), TimelineMessageId("m$order"))

    private fun ledger(size: Int) = InMemoryTimelineStore().apply {
        (1..size).forEach { rows[key(it)] = TimelineStoredRecord(key(it), "opaque", byteArrayOf(it.toByte())) }
    }

    private suspend fun InMemoryTimelineStore.page(position: TimelineReadPosition, maxRows: Int) =
        read(scope) { metadata(position, maxRows) }

    private fun TimelineMetadataPage.orders() = rows.map { it.key.order.toInt() }

    @Test fun aPageReportsItsOwnEdgeRowsAsItsEdges() = runTest {
        val store = ledger(7)

        val tail = store.page(TimelineReadPosition.Tail, 3)
        assertEquals(listOf(5, 6, 7), tail.orders())
        assertEquals(key(5), tail.older)
        assertNull(tail.newer)

        val middle = store.page(TimelineReadPosition.Before(key(5)), 3)
        assertEquals(listOf(2, 3, 4), middle.orders())
        assertEquals(key(2), middle.older)
        assertEquals(key(4), middle.newer)

        val oldest = store.page(TimelineReadPosition.Before(key(2)), 3)
        assertEquals(listOf(1), oldest.orders())
        assertNull(oldest.older)
        assertEquals(key(1), oldest.newer)
    }

    @Test fun pagingFromAnEdgeNeverSkipsARow() = runTest {
        val store = ledger(7)
        val seen = mutableListOf<Int>()
        var page = store.page(TimelineReadPosition.Tail, 3)
        seen += page.orders()
        while (page.older != null) {
            page = store.page(TimelineReadPosition.Before(checkNotNull(page.older)), 3)
            seen.addAll(0, page.orders())
        }
        assertEquals((1..7).toList(), seen)
    }

    @Test fun aroundSplitsThePageLikeRoom() = runTest {
        val store = ledger(7)

        val around = store.page(TimelineReadPosition.Around(key(4)), 3)
        assertEquals(listOf(3, 4, 5), around.orders())
        assertEquals(key(3), around.older)
        assertEquals(key(5), around.newer)

        val wide = store.page(TimelineReadPosition.Around(key(4)), 6)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), wide.orders())
        assertNull(wide.newer)

        val atStart = store.page(TimelineReadPosition.Around(key(1)), 3)
        assertEquals(listOf(1, 2, 3), atStart.orders())
        assertNull(atStart.older)
    }
}
