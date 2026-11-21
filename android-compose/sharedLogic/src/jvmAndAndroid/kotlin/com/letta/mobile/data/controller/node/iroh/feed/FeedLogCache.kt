package com.letta.mobile.data.controller.node.iroh.feed

import java.nio.file.Path

/**
 * The logs the journal holds in memory: at most [maxLoaded] of them, the least recently used idle ones written out and
 * dropped, never one with a running turn. Whenever a log leaves memory (trimmed, or its file deleted by the disk ceiling) the
 * newest seq it issued goes to [floors], so a log created again never reuses one. Callers hold the store lock.
 */
internal class FeedLogCache(
    private val maxLoaded: Int,
    private val floors: FeedSeqFloors,
    private val editor: FeedLogEditor,
) {
    private val logs = LinkedHashMap<JournalKey, FeedLog>()
    private val lastUse = HashMap<Path, Long>()
    private var tick = 0L

    operator fun get(key: JournalKey): FeedLog? = logs[key]?.also(::touch)

    operator fun set(key: JournalKey, log: FeedLog) {
        logs[key] = log
        touch(log)
    }

    /** The keys of every log held, for lookups that need no load. */
    fun keys(): Set<JournalKey> = logs.keys

    /** The files of logs with a running turn: the disk ceiling never deletes these. */
    fun runningFiles(): Set<Path> = logs.values.filter { it.runningTurns > 0 }.mapNotNull { it.file }.toSet()

    /** When [file] was last used (a store-wide counter), for the disk ceiling's least-recently-used order. */
    fun lastUsed(file: Path): Long = lastUse[file] ?: 0L

    /** Forgets the logs whose files the disk ceiling deleted. */
    fun dropFiles(deleted: Collection<Path>) {
        logs.entries.removeAll { entry -> entry.value.file in deleted }
    }

    fun touch(log: FeedLog) {
        log.touched = ++tick
        log.file?.let { lastUse[it] = log.touched }
    }

    /** Keeps at most [maxLoaded] logs: the least recently used idle ones are written out, remembered in the floors, and dropped. */
    fun trim() {
        val surplus = logs.size - maxLoaded
        if (surplus <= 0) return
        logs.values.filter { it.runningTurns == 0 }.sortedBy { it.touched }.take(surplus).forEach {
            editor.flushPending(it)
            floors.note(it.key, it.headSeq)
            logs.remove(it.key)
        }
    }
}
