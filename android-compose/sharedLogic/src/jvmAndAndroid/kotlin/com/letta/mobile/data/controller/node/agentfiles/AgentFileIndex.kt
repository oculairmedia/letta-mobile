package com.letta.mobile.data.controller.node.agentfiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * What the admin reads need to know about every `agents/<id>.json` record without holding or
 * re-parsing the record itself: its id, its newest-first position and whether it is hidden.
 *
 * Each call lists the directory and reads one set of file attributes per file; a record is parsed
 * only when its (modified time at the filesystem's full precision, size) differ from what was parsed last, so a count, the hidden set
 * or the block index no longer re-reads the whole store. What is kept is a few fields per file,
 * dropped when the file goes, and the index never covers more than [maxEntries] files (the newest).
 */
internal class AgentFileIndex(
    private val agentsDir: File,
    private val json: Json,
    private val isHidden: (JsonObject) -> Boolean,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** Told once each time the number of agent files crosses above [maxEntries]: what is past it is not seen. */
    private val onOverCap: (found: Int, max: Int) -> Unit = { _, _ -> },
) {
    /** One readable agent record on disk. */
    class Entry(val file: File, val id: String, val mtimeMs: Long, val hidden: Boolean)

    /**
     * One scan: [entries] as [entries] returns them, and [uncoveredIds] for the files past the cap,
     * named by file (`<id>.json`) since they are never parsed.
     */
    class Scan(val entries: List<Entry>, val uncoveredIds: Set<String>)

    private class Derived(val modified: FileTime, val sizeBytes: Long, val id: String?, val hidden: Boolean)

    private val derived = ConcurrentHashMap<String, Derived>()

    /** The readable agents, newest first. A record that is not a JSON object with an id is left out. */
    fun entries(): List<Entry> = scan().entries

    fun scan(): Scan {
        val files = agentsDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return Scan(emptyList(), emptySet())
        val stamped = files.mapNotNull { file -> stamp(file) }
        val byAge = if (stamped.size > maxEntries) stamped.sortedByDescending { it.mtimeMs } else stamped
        val newest = byAge.take(maxEntries)
        val uncovered = byAge.drop(maxEntries).mapTo(HashSet()) { it.file.name.removeSuffix(SUFFIX) }
        noteCap(stamped.size)
        val live = newest.mapTo(HashSet(newest.size)) { it.file.name }
        derived.keys.retainAll(live)
        val entries = newest
            .mapNotNull { stamp ->
                val info = derive(stamp)
                info?.id?.let { id -> Entry(stamp.file, id, stamp.mtimeMs, info.hidden) }
            }
            .sortedByDescending { it.mtimeMs }
        return Scan(entries, uncovered)
    }

    /**
     * A value that changes whenever an agent file is added, removed or rewritten (path, modified time
     * at full precision, size), or a [companion] file of a covered agent is. A companion is another
     * file the agent's served state is built from (the App Server takes an agent's message ids and
     * last run time from its default conversation). One attribute read per file; a record is parsed
     * only when it changed, to learn its id. Null when the directory cannot be listed.
     */
    fun signature(companion: (String) -> File? = { null }): Long? {
        val files = agentsDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return null
        val stamped = files.mapNotNull { file -> stamp(file) }
        val covered = if (stamped.size > maxEntries) stamped.sortedByDescending { it.mtimeMs }.take(maxEntries) else stamped
        // Sums of per-file mixes, so the listing order does not matter.
        val agents = stamped.fold(stamped.size.toLong()) { sum, stamp -> sum + stamp.mix() }
        val companions = covered.fold(0L) { sum, stamp ->
            val file = derive(stamp)?.id?.let(companion)
            sum + (file?.let(::stamp)?.mix() ?: 0L)
        }
        return agents * MIX_PRIME + companions
    }

    /** The record's derived fields, parsed only when its stamp changed; null when it could not be read. */
    private fun derive(stamp: Stamp): Derived? =
        // A null from parse (the read failed) leaves nothing cached, so the next call retries.
        derived.compute(stamp.file.name) { _, known ->
            known?.takeIf { it.modified == stamp.modified && it.sizeBytes == stamp.sizeBytes } ?: parse(stamp)
        }

    /** Reports once per crossing that records beyond the cap are not seen: counts and hidden sets then leave the oldest out. */
    private fun noteCap(found: Int) {
        val over = found > maxEntries
        if (over && !capWarned) onOverCap(found, maxEntries)
        capWarned = over
    }

    @Volatile private var capWarned = false

    private class Stamp(val file: File, val modified: FileTime, val sizeBytes: Long) {
        val mtimeMs: Long get() = modified.toMillis()

        fun mix(): Long {
            var h = file.path.hashCode().toLong()
            h = h * MIX_PRIME + modified.to(TimeUnit.NANOSECONDS)
            h = h * MIX_PRIME + sizeBytes
            h = (h xor (h ushr 33)) * MIX_MULTIPLIER
            return h xor (h ushr 29)
        }
    }

    private fun stamp(file: File): Stamp? = runCatching {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        Stamp(file, attributes.lastModifiedTime(), attributes.size())
    }.getOrNull()

    /** Null when the file could not be read; a readable file that is not an agent record caches a null id. */
    private fun parse(stamp: Stamp): Derived? {
        val text = runCatching { stamp.file.readText() }.getOrElse { return null }
        val record = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val id = record?.get("id")?.let { runCatching { it.jsonPrimitive.takeUnless { p -> p is JsonNull }?.content }.getOrNull() }
        return Derived(stamp.modified, stamp.sizeBytes, id, hidden = id != null && isHidden(record))
    }

    companion object {
        private const val SUFFIX = ".json"

        /** The most agent files one index covers; the live host has a few thousand. */
        const val DEFAULT_MAX_ENTRIES: Int = 20_000

        private const val MIX_PRIME = 1_000_003L

        /** SplitMix64's 0xbf58476d1ce4e5b9, so one changed field flips about half the bits. */
        private const val MIX_MULTIPLIER = -0x40a7b892e31b1a47L
    }
}
