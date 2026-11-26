package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.runtime.admitsParentStreamDelta
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope

import com.letta.mobile.data.controller.node.iroh.InitiatorReplay
import com.letta.mobile.data.controller.node.iroh.TurnJournal
import com.letta.mobile.data.controller.node.iroh.TurnJournalWriter
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedPage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.io.IOException
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The host's durable, per-conversation journal of turn events: one file per conversation in `directory`, one JSON line per
 * event, stamped with the host's [epoch] (new on every start, persisted so it only grows) and a per-conversation `seq` that
 * keeps growing across restarts. A null `directory` keeps everything in memory.
 *
 * It replaces the in-memory ring that parked a detached turn for its initiator's redial: finished turns now survive a
 * wrapper restart, and any authorized viewer can read the journal from `{epoch, seq}` ([page]). The journal records what the
 * host observed and invents nothing: a turn it never saw end is closed with `terminal_observed: false`, never a terminal delta.
 *
 * Writes never throw: a file that cannot be written leaves that conversation memory-only (telemetry says so), and a file that
 * does not decode is logged and reset.
 */
class FeedJournalStore internal constructor(
    directory: Path?,
    private val retention: FeedRetention,
    clock: () -> Long,
    ops: FileOps,
) : TurnJournal {
    constructor(
        directory: Path? = null,
        retention: FeedRetention = FeedRetention(),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(directory, retention, clock, FileOps())

    private val lock = Any()
    private val root = FeedLogFiles.prepare(directory)
    private val files = FeedLogFiles(root, retention.diskCeilingBytes, ops)
    private val floors = FeedSeqFloors()

    /** This host start: above every epoch an earlier start issued, so a cursor from before a restart never matches. */
    private val issued = FeedEpochs.issue(root, clock())
    val epoch: Long = issued.epoch.value

    /**
     * Whether this start's epoch is on disk. If it is not, a later start could issue the same one and a cursor from this
     * start would match it, so the feed is not served ([FeedRoutes] answers it as unavailable).
     */
    val epochPersisted: Boolean = issued.persisted

    /**
     * Told after every entry a turn writes, outside the store lock, with the journal's key and its head before the write (the
     * seq a viewer that was caught up holds). The live push sets it; it must not block. The default does nothing.
     */
    @Volatile internal var onAppended: (JournalKey, Long) -> Unit = { _, _ -> }

    private val editor = FeedLogEditor(files, retention, FeedEpoch(epoch))
    private val pager = FeedPager(FeedEpoch(epoch))
    private val logs = FeedLogCache(retention.maxLoadedLogs, floors, editor)

    override fun openTurn(agent: AgentId, conversation: ConversationId, turn: String): TurnJournalWriter =
        JournalWriter(JournalKey.of(agent, conversation), journalTurn(turn))

    override fun knowsTurn(agent: AgentId, conversation: ConversationId, turn: String): Boolean = synchronized(lock) {
        existingLog(JournalKey.of(agent, conversation))?.turns?.get(journalTurn(turn))?.opened == true
    }

    override fun replayForInitiator(agent: AgentId, conversation: ConversationId, turn: String): InitiatorReplay? = synchronized(lock) {
        val key = JournalKey.of(agent, conversation)
        val turnId = journalTurn(turn)
        val log = existingLog(key) ?: return null
        val state = log.turns[turnId]
        if (state == null || !state.closed || !state.initiatorMissed || state.delivered) return null
        val own = log.entries.filter { it.turn == turnId }
        val skipped = own.count { it.pointer != null }
        if (skipped > 0) Telemetry.event(TAG, "replay.pointer_skipped", "count" attr skipped)
        // The user echo is the initiator's own prompt, which it re-owns; replaying it would only restate it.
        val replayed = own.filter { it.isStreamDelta && !it.isUserEcho }
        InitiatorReplay(replayed.map { it.payload.toString() }, state.terminalObserved) { delivered(key, turnId) }
    }

    /**
     * The agent the journal recorded as owning [conversation], or null when it holds nothing for it. The bare default
     * conversation belongs to every agent, so it has no recorded owner: its reader must name one.
     */
    fun ownerOf(conversation: ConversationId): AgentId? = synchronized(lock) {
        if (conversation.value == IdFormats.DEFAULT_CONVERSATION_ID) return null
        logs.keys().firstOrNull { it.conversationId == conversation }?.agentId ?: root?.let { FeedDirectory.ownerOnDisk(it, conversation) }
    }

    /**
     * One page of [key]'s journal for a viewer whose last applied position is [from] (null: it has none). A page is a
     * `replay` of what follows the cursor, or a `snapshot` starting the retained journal over when the cursor is absent,
     * from another epoch, behind the compaction horizon or beyond the head.
     */
    fun page(key: JournalKey, from: FeedCursor?, limit: Int): FeedPage = synchronized(lock) {
        pager.page(existingLog(key), from, limit)
    }

    /** The page of a conversation the journal knows nothing of: a snapshot of nothing at the host's epoch. */
    fun emptyPage(from: FeedCursor?): FeedPage = pager.page(null, from, 1)

    private inner class JournalWriter(private val key: JournalKey, private val turn: JournalTurn) : TurnJournalWriter {
        private var opened = false

        /** The turn was closed here: a later detachment note must not reopen it. */
        private var finished = false

        override fun append(deltaJson: String) {
            val delta = parseDelta(deltaJson) ?: return
            val scope = AppServerRuntimeScope(key.agentId.value, key.conversationId.value)
            if (!admitsParentStreamDelta(delta, scope)) return
            guarded("append") {
                val before = synchronized(lock) {
                    val head = heldLog().headSeq.value
                    val log = openLocked()
                    editor.append(log, turn, delta)
                    logs.touch(log)
                    logs.trim()
                    head
                }
                onAppended(key, before)
            }
        }

        /**
         * Recording the detachment and finishing the turn are both decided under the store lock, so a turn that finishes
         * first is only flagged, never reopened.
         */
        override fun noteInitiatorMissedTail() = guarded("missed") {
            synchronized(lock) {
                val log = if (finished) logFor(key) else openLocked()
                editor.markMissed(log, turn)
                logs.trim()
            }
        }

        override fun close(terminalObserved: Boolean) = guarded("close") {
            val before = synchronized(lock) {
                if (!opened) return@synchronized null
                opened = false
                finished = true
                val head = heldLog().headSeq.value
                editor.close(heldLog(), turn, TurnEnd(terminalObserved))
                afterTurnEnded()
                head
            }
            before?.let { onAppended(key, it) }
        }

        /** The log this writer works on; once the turn is open it is pinned in the cache by its running turn, so it is the same instance throughout. */
        private var held: FeedLog? = null

        private fun heldLog(): FeedLog = held ?: logFor(key).also { held = it }

        /** Opens the turn on its log (loading it without trimming), so a loaded log is pinned before anything can evict it. */
        private fun openLocked(): FeedLog {
            val log = heldLog()
            if (!opened) {
                editor.open(log, turn)
                opened = true
            }
            logs.touch(log)
            return log
        }
    }

    private fun delivered(key: JournalKey, turn: JournalTurn) = guarded("delivered") {
        synchronized(lock) { existingLog(key)?.let { editor.markDelivered(it, turn) } }
    }

    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (expected: IOException) {
            Telemetry.event(TAG, "write.failed", "op" attr what, "class" attr expected::class.simpleName, level = Telemetry.Level.WARN)
        }
    }

    private fun parseDelta(deltaJson: String): JsonObject? = try {
        Json.parseToJsonElement(deltaJson) as? JsonObject
    } catch (expected: IllegalArgumentException) {
        Telemetry.event(TAG, "append.undecodable", "class" attr expected::class.simpleName, level = Telemetry.Level.WARN)
        null
    }

    /** The log of [key], created when the journal holds none; a created one starts after any seq an earlier generation issued. */
    private fun logFor(key: JournalKey): FeedLog = loadedLog(key) ?: FeedLog(key, files.fileFor(key)).also {
        it.startAfter(floors.floorFor(key))
        logs[key] = it
    }

    /** The log of [key]: in memory, else read from its file (a turn the last start left running is closed); null when there is none. */
    private fun existingLog(key: JournalKey): FeedLog? = loadedLog(key)?.also { logs.trim() }

    /**
     * [existingLog] without the trim: the writer path loads a log, opens its turn (which pins it) and only then trims, so a
     * log it is about to write to can never be evicted from under it. A journal file that stops short of the floor memory
     * already issued (a degraded journal whose later entries lived only in memory) is not trusted: the conversation starts
     * after the floor.
     */
    private fun loadedLog(key: JournalKey): FeedLog? {
        logs[key]?.let { return it }
        val loaded = files.load(key, floors.floorFor(key)) ?: return null
        logs[key] = loaded
        // A torn last line is cut off first, so the records recovery appends never join it into one malformed line.
        if (loaded.tornTail) files.rewrite(loaded)
        editor.closeOrphaned(loaded)
        editor.enforceBounds(loaded)
        return loaded
    }

    private fun afterTurnEnded() {
        val deleted = files.enforceCeiling(logs.runningFiles(), logs::lastUsed) { key, head -> floors.note(key, head) }
        logs.dropFiles(deleted)
        logs.trim()
    }
}

private const val TAG = "FeedJournal"
