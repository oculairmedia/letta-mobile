package com.letta.mobile.data.controller.node.agentfiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * What the admin reads need to know about every `agents/<id>.json` record without holding or
 * re-parsing the record itself: its id, its newest-first position and whether it is hidden.
 *
 * Each call lists the directory and reads one set of file attributes per file; a record is parsed
 * only when its (modified time, size) differ from what was parsed last, so a count, the hidden set
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

    private class Derived(val mtimeMs: Long, val sizeBytes: Long, val id: String?, val hidden: Boolean)

    private val derived = ConcurrentHashMap<String, Derived>()

    /** The readable agents, newest first. A record that is not a JSON object with an id is left out. */
    fun entries(): List<Entry> {
        val files = agentsDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return emptyList()
        val stamped = files.mapNotNull { file -> stamp(file) }
        val newest = if (stamped.size > maxEntries) stamped.sortedByDescending { it.mtimeMs }.take(maxEntries) else stamped
        noteCap(stamped.size)
        val live = newest.mapTo(HashSet(newest.size)) { it.file.name }
        derived.keys.retainAll(live)
        return newest
            .mapNotNull { stamp ->
                val info = derived.compute(stamp.file.name) { _, known ->
                    known?.takeIf { it.mtimeMs == stamp.mtimeMs && it.sizeBytes == stamp.sizeBytes } ?: parse(stamp)
                }
                info?.id?.let { id -> Entry(stamp.file, id, stamp.mtimeMs, info.hidden) }
            }
            .sortedByDescending { it.mtimeMs }
    }

    /** Reports once per crossing that records beyond the cap are not seen: counts and hidden sets then leave the oldest out. */
    private fun noteCap(found: Int) {
        val over = found > maxEntries
        if (over && !capWarned) onOverCap(found, maxEntries)
        capWarned = over
    }

    @Volatile private var capWarned = false

    private class Stamp(val file: File, val mtimeMs: Long, val sizeBytes: Long)

    private fun stamp(file: File): Stamp? = runCatching {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        Stamp(file, attributes.lastModifiedTime().toMillis(), attributes.size())
    }.getOrNull()

    private fun parse(stamp: Stamp): Derived {
        val record = runCatching { json.parseToJsonElement(stamp.file.readText()).jsonObject }.getOrNull()
        val id = record?.get("id")?.let { runCatching { it.jsonPrimitive.takeUnless { p -> p is JsonNull }?.content }.getOrNull() }
        return Derived(stamp.mtimeMs, stamp.sizeBytes, id, hidden = id != null && isHidden(record))
    }

    companion object {
        private const val SUFFIX = ".json"

        /** The most agent files one index covers; the live host has a few thousand. */
        const val DEFAULT_MAX_ENTRIES: Int = 20_000
    }
}
