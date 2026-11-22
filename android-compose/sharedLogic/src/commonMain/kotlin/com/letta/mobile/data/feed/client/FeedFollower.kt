package com.letta.mobile.data.feed.client

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.feed.wire.FeedDeltaFrame
import com.letta.mobile.data.feed.wire.FeedPage

/** Why a client re-reads `feed.subscribe` from its cursor instead of trusting what it holds. */
enum class FeedResync {
    /** It holds nothing yet. */
    NoCursor,

    /** A frame came from another host start than its cursor. */
    EpochChanged,

    /** A frame continues from a position above its cursor: it missed entries. */
    Gap,

    /** A frame carried no entries but announced a head above its cursor. */
    Marker,

    /** The connection came back: what was pushed meanwhile was missed. */
    Reconnected,
}

/** What a `feed_delta` frame did to a [FeedFollower]. */
sealed interface FeedFrameOutcome {
    /** [entries] were new to the follower and are now applied, in order. */
    data class Applied(val entries: List<FeedDelta>) : FeedFrameOutcome

    /** The frame held nothing the follower lacks (a repeat, or it ends at or below the cursor). */
    data object Stale : FeedFrameOutcome

    /** The follower cannot follow the frame and must re-read from its cursor; it applied nothing. */
    data class Resync(val reason: FeedResync) : FeedFrameOutcome
}

/** What a `feed.subscribe` page did to a [FeedFollower]. */
data class FeedPageOutcome(
    /** The entries to apply, in order; the whole retained journal when [replaced]. */
    val entries: List<FeedDelta>,
    /** The page started the journal over: the caller drops what it holds before applying [entries]. */
    val replaced: Boolean,
    /** The page is not the end: read again from the follower's cursor. */
    val hasMore: Boolean,
    /** The page left the cursor behind where pushes had already taken it: read again to catch up. */
    val regressed: Boolean,
)

/**
 * The position of one conversation's feed on a client, and the rules for moving it. It never applies a frame it cannot
 * follow: an epoch that is not its cursor's, a frame that continues from above its cursor and a marker above its cursor are
 * all a [FeedFrameOutcome.Resync], and a seq hole is never a loss signal (a cumulative snapshot replaces its predecessor under
 * a fresh seq). Pure state, no I/O and no locking: the caller serializes its calls.
 */
class FeedFollower {
    /** The last position applied; null until the first page. */
    var cursor: FeedCursor? = null
        private set

    /** Applies [frame] when it continues the cursor. */
    fun onFrame(frame: FeedDeltaFrame): FeedFrameOutcome {
        val held = cursor ?: return FeedFrameOutcome.Resync(FeedResync.NoCursor)
        val resync = resyncFor(frame, held)
        if (resync != null) return FeedFrameOutcome.Resync(resync)
        val fresh = frame.entries.filter { it.seq > held.seq }
        if (fresh.isEmpty() && frame.seq <= held.seq) return FeedFrameOutcome.Stale
        cursor = FeedCursor(held.epoch, maxOf(held.seq, frame.seq))
        return FeedFrameOutcome.Applied(fresh)
    }

    private fun resyncFor(frame: FeedDeltaFrame, held: FeedCursor): FeedResync? = when {
        frame.epoch != held.epoch -> FeedResync.EpochChanged
        frame.afterSeq > held.seq -> if (frame.entries.isEmpty()) FeedResync.Marker else FeedResync.Gap
        else -> null
    }

    /** Applies [page], a read from [cursor]. */
    fun onPage(page: FeedPage): FeedPageOutcome {
        val held = cursor
        val replaced = page.mode == ConversationFeedWire.MODE_SNAPSHOT
        val sameEpoch = held != null && held.epoch == page.cursor.epoch
        val entries = if (replaced || held == null) page.entries else page.entries.filter { it.seq > held.seq }
        val regressed = replaced && sameEpoch && held != null && page.cursor.seq < held.seq
        cursor = when {
            replaced || held == null || !sameEpoch -> page.cursor
            else -> FeedCursor(held.epoch, maxOf(held.seq, page.cursor.seq))
        }
        return FeedPageOutcome(entries, replaced, page.hasMore, regressed)
    }

    /** Forgets the position: the next read is a snapshot. */
    fun reset() {
        cursor = null
    }
}
