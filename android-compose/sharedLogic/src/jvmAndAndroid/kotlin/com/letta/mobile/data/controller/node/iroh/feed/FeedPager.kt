package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.feed.wire.FeedPage

/**
 * Builds the page `feed.subscribe` answers: a replay of what follows the caller's cursor, or a snapshot that starts the
 * retained journal over when that cursor is unusable. Pure over a [FeedLog]; the store holds the lock around it.
 */
internal class FeedPager(private val epoch: FeedEpoch) {
    /** The page of [log] (null: the journal holds nothing) for a viewer at [from], at most [limit] entries. */
    fun page(log: FeedLog?, from: FeedCursor?, limit: Int): FeedPage {
        val horizon = horizonOf(log)
        val resync = resyncReason(from, horizon)
        val start = if (resync == null) FeedSeq(from?.seq ?: 0) else horizon.base
        val taken = takeEntries(log, start, limit.coerceIn(1, ConversationFeedWire.MAX_LIMIT))
        return FeedPage(
            mode = if (resync == null) ConversationFeedWire.MODE_REPLAY else ConversationFeedWire.MODE_SNAPSHOT,
            resyncReason = resync,
            cursor = FeedCursor(epoch.value, cursorSeq(taken, start, horizon).value),
            baseSeq = horizon.base.value,
            headSeq = horizon.head.value,
            hasMore = taken.more,
            entries = taken.entries.map { FeedDelta(it.epoch.value, it.seq.value, it.kind, it.turn.value, it.payload, it.pointer) },
        )
    }

    /** Where the caller continues: after the last entry it was given while more remain, else at the head (never behind where it was). */
    private fun cursorSeq(taken: Taken, start: FeedSeq, horizon: Horizon): FeedSeq {
        val last = taken.entries.lastOrNull()?.seq ?: maxOf(start, horizon.base)
        return if (taken.more) last else maxOf(last, horizon.head)
    }

    private fun resyncReason(from: FeedCursor?, horizon: Horizon): String? = when {
        from == null -> ConversationFeedWire.RESYNC_NO_CURSOR
        from.epoch != epoch.value -> ConversationFeedWire.RESYNC_EPOCH_CHANGED
        FeedSeq(from.seq) < horizon.base -> ConversationFeedWire.RESYNC_BEHIND_HORIZON
        FeedSeq(from.seq) > horizon.head -> ConversationFeedWire.RESYNC_AHEAD_OF_HEAD
        else -> null
    }

    /** The first entries after [after] that fit the limit and the page byte budget, and whether more follow. */
    private fun takeEntries(log: FeedLog?, after: FeedSeq, limit: Int): Taken {
        val taken = ArrayList<FeedEntry>()
        var bytes = NO_BYTES
        for (entry in log?.entries.orEmpty().filter { it.seq > after }) {
            if (pageIsFull(taken.size, limit, bytes + entry.bytes)) return Taken(taken, more = true)
            taken += entry
            bytes += entry.bytes
        }
        return Taken(taken, more = false)
    }

    /**
     * A page is full when it holds [limit] entries, or when adding one more would pass the byte budget (a page always holds
     * at least one entry, however large).
     */
    private fun pageIsFull(count: Int, limit: Int, bytesWithNext: ByteCount): Boolean =
        count >= limit || (count > 0 && bytesWithNext > PAGE_BUDGET)

    private class Taken(val entries: List<FeedEntry>, val more: Boolean)

    /** The compaction horizon and head of a journal (both zero when it holds nothing). */
    private class Horizon(val base: FeedSeq, val head: FeedSeq)

    private fun horizonOf(log: FeedLog?) = Horizon(log?.baseSeq ?: NO_SEQ, log?.headSeq ?: NO_SEQ)
}

private val PAGE_BUDGET = ByteCount(ConversationFeedWire.MAX_PAGE_BYTES.toLong())
