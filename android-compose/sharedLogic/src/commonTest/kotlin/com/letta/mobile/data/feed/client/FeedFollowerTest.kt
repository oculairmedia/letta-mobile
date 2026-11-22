package com.letta.mobile.data.feed.client

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.feed.wire.FeedDeltaFrame
import com.letta.mobile.data.feed.wire.FeedPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/** The rules that decide, per frame and per page, whether a client applies what it was sent or reads from its cursor again. */
class FeedFollowerTest {
    private fun entry(seq: Long, epoch: Long = 1) =
        FeedDelta(epoch, seq, ConversationFeedWire.KIND_STREAM_DELTA, "turn", payload = JsonObject(emptyMap()))

    private fun frame(after: Long, vararg seqs: Long, epoch: Long = 1, head: Long = seqs.maxOrNull() ?: after) = FeedDeltaFrame(
        type = ConversationFeedWire.FRAME_TYPE, conversation = "c", agentId = "a", epoch = epoch, seq = head, afterSeq = after,
        entries = seqs.map { entry(it, epoch) },
    )

    private fun page(mode: String, cursorSeq: Long, vararg seqs: Long, epoch: Long = 1, more: Boolean = false) = FeedPage(
        mode = mode,
        resyncReason = ConversationFeedWire.RESYNC_NO_CURSOR.takeIf { mode == ConversationFeedWire.MODE_SNAPSHOT },
        cursor = FeedCursor(epoch, cursorSeq), baseSeq = 0, headSeq = cursorSeq, hasMore = more, entries = seqs.map { entry(it, epoch) },
    )

    private fun following(upTo: Long = 4) = FeedFollower().also {
        it.onPage(page(ConversationFeedWire.MODE_SNAPSHOT, upTo, *(1..upTo).toList().toLongArray()))
    }

    @Test
    fun aFrameBeforeAnyPageIsAResyncNotAGuess() {
        assertEquals(FeedFrameOutcome.Resync(FeedResync.NoCursor), FeedFollower().onFrame(frame(0, 1)))
    }

    @Test
    fun aFrameThatContinuesTheCursorIsAppliedAndMovesIt() {
        val follower = following()
        val outcome = follower.onFrame(frame(4, 5, 7))
        assertEquals(listOf(5L, 7L), assertIs<FeedFrameOutcome.Applied>(outcome).entries.map { it.seq })
        assertEquals(7L, follower.cursor?.seq)
    }

    @Test
    fun aSeqHoleInsideAFrameIsNeverALossSignal() {
        val follower = following()
        assertIs<FeedFrameOutcome.Applied>(follower.onFrame(frame(4, 6)), "seq 5 was replaced by 6: a hole, not a gap")
    }

    @Test
    fun aFrameThatContinuesFromAboveTheCursorMissedEntriesAndAppliesNothing() {
        val follower = following()
        assertEquals(FeedFrameOutcome.Resync(FeedResync.Gap), follower.onFrame(frame(6, 7)))
        assertEquals(4L, follower.cursor?.seq, "a frame it cannot follow leaves the cursor where it was")
    }

    @Test
    fun aFrameOfAnotherEpochIsAResync() {
        assertEquals(FeedFrameOutcome.Resync(FeedResync.EpochChanged), following().onFrame(frame(0, 1, epoch = 2)))
    }

    @Test
    fun aMarkerAboveTheCursorIsAResyncAndAMarkerAtItIsNot() {
        assertEquals(FeedFrameOutcome.Resync(FeedResync.Marker), following().onFrame(frame(9, head = 9)))
        assertEquals(FeedFrameOutcome.Stale, following().onFrame(frame(4, head = 4)))
    }

    @Test
    fun aRepeatedFrameIsStaleAndAppliesNothing() {
        val follower = following()
        follower.onFrame(frame(4, 5))
        assertEquals(FeedFrameOutcome.Stale, follower.onFrame(frame(4, 5)))
    }

    @Test
    fun aSnapshotPageReplacesWhatTheClientHoldsAndAReplayPageAppendsOnlyWhatIsNew() {
        val follower = following()
        val replay = follower.onPage(page(ConversationFeedWire.MODE_REPLAY, 6, 4, 5, 6))
        assertFalse(replay.replaced)
        assertEquals(listOf(5L, 6L), replay.entries.map { it.seq }, "seq 4 is already applied")

        val snapshot = follower.onPage(page(ConversationFeedWire.MODE_SNAPSHOT, 3, 1, 2, 3, epoch = 2))
        assertTrue(snapshot.replaced)
        assertEquals(FeedCursor(2, 3), follower.cursor)
        assertEquals(3, snapshot.entries.size)
    }
}
