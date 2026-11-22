package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.ConversationViewers
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.ObserverWriteQueue
import com.letta.mobile.data.controller.node.iroh.ViewerHandle
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedDeltaFrame
import com.letta.mobile.data.feed.wire.FeedPage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pushes the entries a turn writes to the journal to the connections viewing that conversation, as `feed_delta` frames (the
 * live half of the conversation feed contract). The journal's writer tells it after each write; it does nothing at all while
 * no connection views the conversation, and otherwise coalesces what was written in one frame budget into one frame per
 * conversation, so a burst of snapshots is one write, not one per snapshot.
 *
 * A frame carries `after_seq`, the head before the first entry it carries, so a viewer whose cursor is below it knows it
 * missed entries (a frame it never got, a frame dropped here) and re-reads `feed.subscribe` from its cursor. Nothing here decides what a viewer holds: a viewer
 * whose queue is full simply misses frames, and the next one it receives tells it so. Each viewer is authorized when the frame
 * is sent, by the same rule `feed.subscribe` applies: it must have advertised the push capability, hold `chat.read`, be allowed
 * to read the conversation under the name it views it by, and have the owning agent in scope. Telemetry carries counts only.
 */
class FeedPusher(
    private val scope: CoroutineScope,
    private val journal: FeedJournalStore,
    private val viewersFor: ConversationViewers,
    private val isViewed: (conversation: ConversationId) -> Boolean,
    private val frameBudgetMs: Long = FRAME_BUDGET_MS,
) {
    private val lock = Any()

    /** Conversations written since the last frame, each with the head before the first of those writes: the seq its frame continues from. */
    private val dirty = LinkedHashMap<JournalKey, Long>()
    private val writes = ObserverWriteQueue(scope)
    private val queued = ConcurrentHashMap<ViewerHandle, Int>()

    init {
        journal.onAppended = ::appended
    }

    /** The journal wrote to [key]; its head was [before] first. Cheap, and free when nobody views the conversation. */
    private fun appended(key: JournalKey, before: Long) {
        if (namesOf(key).none { isViewed(ConversationId(it)) }) return
        val start = synchronized(lock) {
            val idle = dirty.isEmpty()
            // Writers of one conversation can report out of order; the lowest head is where the frame must continue from.
            dirty.merge(key, before, ::minOf)
            idle
        }
        if (start) scope.launch { flushAfterBudget() }
    }

    private suspend fun flushAfterBudget() {
        delay(frameBudgetMs)
        val batch = synchronized(lock) { LinkedHashMap(dirty).also { dirty.clear() } }
        batch.forEach { (key, after) -> pushTo(key, after) }
    }

    private suspend fun pushTo(key: JournalKey, after: Long) {
        val audience = audienceOf(key)
        if (audience.isEmpty()) return
        val page = journal.page(key, FeedCursor(journal.epoch, after), PUSH_MAX_ENTRIES)
        val frame = frameOf(key, after, page)
        val delivered = audience.count { send(it, frame) }
        Telemetry.event(
            TAG, "pushed", "viewers" attr audience.size, "delivered" attr delivered, "entries" attr page.entries.size,
            "resync" attr page.entries.isEmpty(),
        )
    }

    /**
     * The frame for [page], read from [after]. A page that cannot carry every entry after [after] (retention compacted past it,
     * or it holds more than a frame should) becomes a resync marker: no entries, and `after_seq` at the head, so every viewer
     * that is not already at the head re-reads.
     */
    private fun frameOf(key: JournalKey, after: Long, page: FeedPage): String {
        val complete = page.mode == ConversationFeedWire.MODE_REPLAY && !page.hasMore
        val frame = FeedDeltaFrame(
            type = ConversationFeedWire.FRAME_TYPE,
            conversation = key.conversationId.value,
            agentId = key.agentId.value,
            epoch = journal.epoch,
            seq = page.headSeq,
            afterSeq = if (complete) after else page.headSeq,
            entries = if (complete) page.entries else emptyList(),
        )
        return LettaJson.wire.encodeToJsonElement(FeedDeltaFrame.serializer(), frame).toString()
    }

    /** Hands [frame] to [viewer]'s ordered queue; a viewer already [MAX_QUEUED_FRAMES] behind drops it and learns from the next. */
    private fun send(viewer: ViewerHandle, frame: String): Boolean {
        val behind = queued.merge(viewer, 1, Int::plus) ?: 1
        if (behind > MAX_QUEUED_FRAMES) {
            release(viewer)
            return false
        }
        writes.enqueue(viewer) {
            try {
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { viewer.writeFrame(frame) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                release(viewer)
            }
        }
        return true
    }

    private fun release(viewer: ViewerHandle) {
        queued.merge(viewer, -1) { held, step -> (held + step).takeIf { it > 0 } }
    }

    /** The viewers allowed to receive [key]'s frames: each is checked under the conversation name it registered with. */
    private suspend fun audienceOf(key: JournalKey): List<ViewerHandle> =
        namesOf(key).flatMap { name -> viewersFor(ConversationId(name)).filter { mayReceive(it, key, name) } }.distinct()

    private fun mayReceive(viewer: ViewerHandle, key: JournalKey, viewedAs: String): Boolean {
        if (!viewer.receivesFeedPushes()) return false
        val context = viewer.adminContext() ?: return false
        val method = IrohPeerCapabilities.forAdminMethod(ConversationFeedWire.SUBSCRIBE)
        return IrohPeerCapabilities.isAllowed(context.grantedCapabilities, method) && mayReadFeed(viewedAs, key.agentId, context)
    }

    /** The names viewers register [key] under: its own, and for an agent's default conversation also the `conv-default-<agent>` alias. */
    private fun namesOf(key: JournalKey): List<String> {
        val own = key.conversationId.value
        return if (own == IdFormats.DEFAULT_CONVERSATION_ID) listOf(own, IdFormats.defaultConversationIdFor(key.agentId.value)) else listOf(own)
    }
}

private const val TAG = "FeedPush"

/** One frame per conversation per this long: about one display frame. */
private const val FRAME_BUDGET_MS = 32L

/** Most entries one frame carries; a burst past it is a resync marker. */
private const val PUSH_MAX_ENTRIES = 128

/** Frames a viewer may have waiting; one further behind drops them and resyncs on the next. */
private const val MAX_QUEUED_FRAMES = 4

/** How long one write may take before the viewer is treated as wedged for that frame. */
private const val WRITE_TIMEOUT_MS = 5_000L
