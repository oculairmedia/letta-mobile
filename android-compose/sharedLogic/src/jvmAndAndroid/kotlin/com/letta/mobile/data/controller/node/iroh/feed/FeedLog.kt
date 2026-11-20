package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.resolveConversationReadTarget
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/**
 * One conversation's journal, as the host keys it: the owning agent and the conversation. An agent's default
 * conversation is one key under either spelling (`default` with the agent, or `conv-default-<agent>`).
 */
data class JournalKey(val agentId: AgentId, val conversationId: ConversationId) {
    companion object {
        /** The key of [conversation] owned by [agent]; an alias names its own owner, which wins. */
        fun of(agent: AgentId, conversation: ConversationId): JournalKey {
            val target = resolveConversationReadTarget(conversation)
            return JournalKey(target?.second ?: agent, target?.first ?: conversation)
        }
    }
}

/** What one entry holds: exactly one of an inline [payload] and a [pointer] to an omitted one. */
internal class EntryBody private constructor(val payload: JsonObject?, val pointer: JsonObject?) {
    /** The JSON the entry's line carries. */
    val json: JsonObject get() = checkNotNull(payload ?: pointer)

    companion object {
        fun inline(payload: JsonObject) = EntryBody(payload, null)

        fun pointer(pointer: JsonObject) = EntryBody(null, pointer)

        /** [payload] or [pointer] when exactly one is set; null when both or neither are. */
        fun of(payload: JsonObject?, pointer: JsonObject?): EntryBody? =
            if ((payload == null) == (pointer == null)) null else EntryBody(payload, pointer)
    }
}

/** An entry about to be added: what the editor knows before the log assigns its seq. */
internal class EntryDraft(val turn: JournalTurn, val kind: String, val body: EntryBody, val textKey: TextKey? = null) {
    /** What the entry costs on the wire and on disk: its body, its turn id (repeated on every entry) and the envelope. */
    fun encodedSize(): ByteCount {
        val bytes = body.json.toString().toByteArray(Charsets.UTF_8).size + turn.value.toByteArray(Charsets.UTF_8).size
        return ByteCount(bytes.toLong() + FeedLogCodec.ENTRY_OVERHEAD_BYTES)
    }

    /** Whether this draft is the next snapshot of the message [tail] already holds, so it replaces it. */
    fun replaces(tail: FeedEntry?): Boolean = textKey != null && tail != null && tail.turn == turn && tail.textKey == textKey
}

/** One journal entry: a [ConversationFeedWire] delta before it is put on the wire. */
internal class FeedEntry(
    val epoch: FeedEpoch,
    val seq: FeedSeq,
    val draft: EntryDraft,
    val bytes: ByteCount,
) {
    val kind: String get() = draft.kind
    val turn: JournalTurn get() = draft.turn
    val payload: JsonObject? get() = draft.body.payload
    val pointer: JsonObject? get() = draft.body.pointer
    val textKey: TextKey? get() = draft.textKey

    /** The seq of the persisted entry this one replaces when it is written (a superseded cumulative text snapshot). */
    var supersedes: FeedSeq? = null

    /** The entry's line is in the file. */
    var persisted: Boolean = false

    val isStreamDelta: Boolean get() = kind == ConversationFeedWire.KIND_STREAM_DELTA && payload != null
}

/** What the host knows of one turn of a journal. */
internal class FeedTurn {
    /** Its `turn_open` entry was written. */
    var opened: Boolean = false

    /** Its `turn_closed` entry was written. */
    var closed: Boolean = false

    /** Whether a real terminal went through the journal before it closed. */
    var terminalObserved: Boolean = false

    /** The initiator stopped receiving this turn: a re-sent input is owed the tail. */
    var initiatorMissed: Boolean = false

    /** The owed tail reached the initiator. */
    var delivered: Boolean = false
}

/**
 * The in-memory state of one conversation's journal. Plain data and bookkeeping: [FeedJournalStore] holds the
 * lock, writes the file and decides when to trim.
 */
internal class FeedLog(val key: JournalKey, val file: Path?) {
    /** The file ended in a partial line (a write the process died in); a rewrite makes it appendable again. */
    var tornTail: Boolean = false

    val entries = ArrayDeque<FeedEntry>()
    val turns = LinkedHashMap<JournalTurn, FeedTurn>()

    /** Entries at or below this seq may be gone: a cursor below it must resync. */
    var baseSeq: FeedSeq = NO_SEQ

    /** The newest seq issued. */
    var headSeq: FeedSeq = NO_SEQ

    /** Bytes of the entries held. */
    var bytes: ByteCount = NO_BYTES

    /** Bytes the file holds: its live lines and the lines since superseded or dropped. */
    var fileBytes: ByteCount = NO_BYTES

    /** Of [fileBytes], what no longer matters; a rewrite reclaims it. */
    var garbageBytes: ByteCount = NO_BYTES

    /** Turns of this log still running here; a log with one is never evicted. */
    var runningTurns: Int = 0

    /** Consecutive entries that replaced their predecessor since the tail was last written. */
    var supersedeRun: Int = 0

    /** The file could not be written; the log lives in memory only. */
    var degraded: Boolean = false

    /** Store-wide use counter at the last touch: the least recently used log is evicted first. */
    var touched: Long = 0

    /** Starts an empty log after [floor]: its first entry is the one after, so no seq of an earlier generation is reused. */
    fun startAfter(floor: FeedSeq) {
        baseSeq = floor
        headSeq = floor
    }

    fun nextSeq(): FeedSeq = headSeq.next().also { headSeq = it }

    fun tail(): FeedEntry? = entries.lastOrNull()

    fun add(entry: FeedEntry) {
        entries.addLast(entry)
        bytes += entry.bytes
    }

    /** Replaces the tail entry with [entry]; the replaced one is garbage when it was already written. */
    fun replaceTail(entry: FeedEntry) {
        val old = entries.removeLast()
        bytes -= old.bytes
        if (old.persisted) garbageBytes += old.bytes
        add(entry)
    }

    /**
     * Drops the oldest finished turn's entries from the front of the log; false when the turn at the front still runs or
     * nothing is held. The turn's state (that it was closed, how it ended, who is owed its tail) stays while any of its entries
     * remain ([forgetDrainedTurns] removes it after), so a turn whose later entries sit behind another turn's (interleaved
     * turns) is still known and replayable.
     */
    fun dropOldestFinishedTurn(): Boolean {
        val front = entries.firstOrNull() ?: return false
        if (turns[front.turn]?.closed == false) return false
        while (entries.firstOrNull()?.turn == front.turn) dropFront()
        return true
    }

    /** After compaction: forget the turns that are finished and have no entry left; a running turn is always kept. */
    fun forgetDrainedTurns() {
        val held = entries.mapTo(HashSet()) { it.turn }
        turns.entries.removeAll { (turn, state) -> state.closed && turn !in held }
    }

    /** Drops the oldest entry whatever its turn: the hard bound on a single enormous turn. */
    fun dropFront() {
        val dropped = entries.removeFirst()
        bytes -= dropped.bytes
        if (dropped.persisted) garbageBytes += dropped.bytes
        baseSeq = dropped.seq
    }
}
