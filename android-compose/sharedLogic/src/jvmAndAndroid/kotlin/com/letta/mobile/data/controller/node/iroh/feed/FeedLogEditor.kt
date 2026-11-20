package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.deltaEndsRun
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Every change to a [FeedLog]: appending a turn's events, closing the turn, the flags that say who is owed the tail, and
 * the retention that keeps the log bounded. Callers hold the store lock.
 *
 * A cumulative text snapshot replaces the previous snapshot of the same message at the log's tail, under a fresh seq (a viewer
 * that applied the old one still receives the new one). The first snapshot of a message is written at once; later ones replace
 * it in memory and only every [FeedRetention.textFlushEvery]-th, and the last one when the message ends, is written, so a crash
 * loses at most that many of the newest snapshots of the message being streamed.
 */
internal class FeedLogEditor(
    private val files: FeedLogFiles,
    private val retention: FeedRetention,
    private val epoch: FeedEpoch,
) {
    fun open(log: FeedLog, turn: JournalTurn): FeedTurn {
        val state = log.turns.getOrPut(turn) { FeedTurn() }
        if (!state.opened) {
            addEntry(log, EntryDraft(turn, ConversationFeedWire.KIND_TURN_OPEN, EntryBody.inline(JsonObject(emptyMap()))))
        }
        state.opened = true
        state.closed = false
        log.runningTurns += 1
        return state
    }

    fun append(log: FeedLog, turn: JournalTurn, delta: JsonObject) {
        val body = DeltaBody.of(delta)
        val textKey = TextKeys.of(delta).takeIf { body.payload != null }
        addEntry(log, EntryDraft(turn, ConversationFeedWire.KIND_STREAM_DELTA, body, textKey))
        if (deltaEndsRun(delta)) log.turns[turn]?.terminalObserved = true
    }

    /**
     * Records that the host stopped observing [turn]. [end] says whether a real terminal went through; a terminal already
     * journaled counts too, and nothing else makes `terminal_observed` true.
     */
    fun close(log: FeedLog, turn: JournalTurn, end: TurnEnd) {
        val state = log.turns[turn] ?: return
        state.terminalObserved = end.terminalObserved || state.terminalObserved
        val payload = FeedLogCodec.closedPayload(state.terminalObserved, end.hostRestarted)
        addEntry(log, EntryDraft(turn, ConversationFeedWire.KIND_TURN_CLOSED, EntryBody.inline(payload)))
        state.closed = true
        log.runningTurns = (log.runningTurns - 1).coerceAtLeast(0)
        flushPending(log)
        files.sync(log)
        reclaimObsolete(log)
    }

    fun markMissed(log: FeedLog, turn: JournalTurn) {
        log.turns.getOrPut(turn) { FeedTurn() }.initiatorMissed = true
        files.write(log, FeedLogCodec.missedFlag(turn))
    }

    fun markDelivered(log: FeedLog, turn: JournalTurn) {
        log.turns[turn]?.delivered = true
        files.write(log, FeedLogCodec.deliveredFlag(turn))
    }

    /**
     * A turn the previous host start left running was never seen to end. Close it with a fact, not a terminal
     * (`terminal_observed` only when a real terminal is in the journal), and owe its initiator the tail unless it got it.
     */
    fun closeOrphaned(log: FeedLog) {
        val orphans = log.turns.filter { (_, state) -> state.opened && !state.closed }.keys.toList()
        orphans.forEach { turn ->
            val sawTerminal = log.entries.any { it.turn == turn && it.payload?.let(::deltaEndsRun) == true }
            // The owed-tail marker goes first: a crash between the two records can then only leave a turn open (recovered
            // again on the next start), never a closed turn whose initiator was forgotten.
            if (log.turns.getValue(turn).delivered.not()) markMissed(log, turn)
            log.runningTurns += 1
            close(log, turn, TurnEnd(terminalObserved = sawTerminal, hostRestarted = true))
        }
    }

    fun flushPending(log: FeedLog) {
        val tail = log.tail() ?: return
        if (!tail.persisted) persist(log, tail)
    }

    private fun addEntry(log: FeedLog, draft: EntryDraft) {
        val entry = FeedEntry(epoch, log.nextSeq(), draft, draft.encodedSize())
        if (draft.replaces(log.tail())) replaceSnapshot(log, entry) else addNew(log, entry)
        enforceBounds(log)
    }

    /** The next snapshot of the message at the tail: it replaces it, and is written only every few replacements. */
    private fun replaceSnapshot(log: FeedLog, entry: FeedEntry) {
        val tail = checkNotNull(log.tail())
        entry.supersedes = if (tail.persisted) tail.seq else tail.supersedes
        log.replaceTail(entry)
        log.supersedeRun += 1
        if (log.supersedeRun >= retention.textFlushEvery) {
            persist(log, entry)
            reclaimObsolete(log)
        }
    }

    /** Rewrites the file when the superseded snapshot records it still holds outweigh the live ones, also while the turn runs. */
    private fun reclaimObsolete(log: FeedLog) {
        if (log.garbageBytes > Retention.rewriteThreshold(log.fileBytes)) files.rewrite(log)
    }

    /**
     * An entry that starts something new: the tail is settled first and the entry is written at once, so a host that dies
     * mid-message keeps the text so far.
     */
    private fun addNew(log: FeedLog, entry: FeedEntry) {
        flushPending(log)
        log.add(entry)
        log.supersedeRun = 0
        persist(log, entry)
    }

    private fun persist(log: FeedLog, entry: FeedEntry) {
        files.write(log, FeedLogCodec.entry(entry))
        entry.persisted = true
        log.supersedeRun = 0
    }

    /** Over the count or byte bound: drop finished turns oldest first (then the running turn's oldest entries) down to three quarters. */
    fun enforceBounds(log: FeedLog) {
        if (!Retention.exceededBy(log, retention)) return
        while (!Retention.reachedTarget(log, retention)) {
            if (!log.dropOldestFinishedTurn()) log.dropFront()
        }
        log.forgetDrainedTurns()
        Telemetry.event(TAG, "retention.compacted", "entries" attr log.entries.size, "bytes" attr log.bytes.value)
        files.rewrite(log)
    }

}

private const val TAG = "FeedJournal"

/** How a turn ended as far as the host saw: whether a real terminal went through, and whether the host restarted under it. */
internal class TurnEnd(val terminalObserved: Boolean, val hostRestarted: Boolean = false)

/** The retention bounds of conversations and the point they compact down to (three quarters of a bound). */
internal object Retention {
    private const val KEEP_NUMERATOR = 3
    private const val KEEP_DENOMINATOR = 4
    private val MIN_GARBAGE = ByteCount(64L * 1024)

    fun exceededBy(log: FeedLog, retention: FeedRetention): Boolean =
        log.entries.size > retention.maxEntriesPerConversation || log.bytes > ByteCount(retention.maxBytesPerConversation)

    fun reachedTarget(log: FeedLog, retention: FeedRetention): Boolean {
        val entriesKept = log.entries.size <= scaled(retention.maxEntriesPerConversation.toLong())
        val bytesKept = log.bytes <= ByteCount(scaled(retention.maxBytesPerConversation))
        return entriesKept && bytesKept
    }

    /** Three quarters of [limit], rounded down, without overflowing for any limit (the quotient and the remainder scale apart). */
    fun scaled(limit: Long): Long =
        limit / KEEP_DENOMINATOR * KEEP_NUMERATOR + limit % KEEP_DENOMINATOR * KEEP_NUMERATOR / KEEP_DENOMINATOR

    /** Garbage a file may hold before a rewrite reclaims it: half the file, and never under the minimum. */
    fun rewriteThreshold(fileBytes: ByteCount): ByteCount = maxOf(MIN_GARBAGE, ByteCount(fileBytes.value / 2))
}

/** The body of a `stream_delta` entry: the delta inline, or a pointer when it is over the inline cap. */
internal object DeltaBody {
    fun of(delta: JsonObject): EntryBody {
        val size = delta.toString().toByteArray(Charsets.UTF_8).size
        if (size <= ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES) return EntryBody.inline(delta)
        // A terminal is never a pointer (replay skips pointers and the host must not lose a terminal): it is shortened instead.
        return if (deltaEndsRun(delta)) EntryBody.inline(FeedTerminals.compact(delta)) else EntryBody.pointer(pointerFor(delta, size))
    }

    private fun pointerFor(delta: JsonObject, byteLen: Int): JsonObject = buildJsonObject {
        put("reason", "too_large")
        put(ConversationFeedWire.POINTER_BYTE_LEN, byteLen)
        (delta["message_type"] as? JsonPrimitive)?.contentOrNull?.let { put(ConversationFeedWire.POINTER_MESSAGE_TYPE, it) }
        (delta["id"] as? JsonPrimitive)?.contentOrNull?.let { put(ConversationFeedWire.POINTER_MESSAGE_ID, it) }
    }
}

/** Which text message a delta is a cumulative snapshot of; null when it is not text, or too large to be kept inline. */
internal object TextKeys {
    private val TEXT_TYPES = setOf("assistant_message", "reasoning_message", "hidden_reasoning_message")

    fun of(delta: JsonObject): TextKey? {
        val type = (delta["message_type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in TEXT_TYPES } ?: return null
        val logical = (delta["logical_message_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        return TextKey("$type|$logical")
    }
}
