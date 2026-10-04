package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineMetadataPage
import com.letta.mobile.data.timeline.TimelinePageKey
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineStoredRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-8p8mj: the frame harness's store must page the way the shipped stores do, or Paging
 * walks past rows at every page boundary and the harness reports a settle that never finishes.
 */
class UiFrameLedgerTest {
    private val ledger = UiFrameLedger().apply {
        (1L..9L).forEach { put(TimelineStoredRecord(key(it), "event", byteArrayOf())) }
    }

    @Test
    fun walkingOutFromAnAnchorReachesEveryRowExactlyOnce() {
        // What Paging does after a refresh around an anchor: Before(older) and After(newer), a page
        // at a time. A single-row window puts a boundary on each side of the anchor.
        val anchor = ledger.page(TimelineReadPosition.Around(key(5)), 1)
        val seen = mutableListOf<Long>()
        seen += anchor.orders()
        var older = anchor.older
        while (older != null) {
            val page = ledger.page(TimelineReadPosition.Before(older), 2)
            seen.addAll(0, page.orders())
            older = page.older
        }
        var newer = anchor.newer
        while (newer != null) {
            val page = ledger.page(TimelineReadPosition.After(newer), 2)
            seen += page.orders()
            newer = page.newer
        }

        assertEquals((1L..9L).toList(), seen)
    }

    @Test
    fun boundariesAreThePagesOwnEdgesAndNullAtARealEnd() {
        val tail = ledger.page(TimelineReadPosition.Tail, 3)

        assertEquals(listOf(7L, 8L, 9L), tail.orders())
        assertEquals(key(7), tail.older)
        assertNull(tail.newer)
    }

    @Test
    fun aroundIsAWindowCentredOnTheKeyLikeRoom() {
        assertEquals(listOf(4L, 5L, 6L, 7L), ledger.page(TimelineReadPosition.Around(key(5)), 4).orders())
        assertEquals(listOf(5L), ledger.page(TimelineReadPosition.Around(key(5)), 1).orders())
    }

    private fun TimelineMetadataPage.orders(): List<Long> = rows.map { it.key.order }

    private fun key(order: Long) = TimelinePageKey(order, TimelineMessageId("row-$order"))
}
