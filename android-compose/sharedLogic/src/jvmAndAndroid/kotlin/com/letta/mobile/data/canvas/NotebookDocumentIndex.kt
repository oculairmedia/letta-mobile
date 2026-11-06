package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Which notebook documents a repository holds (`notebook-documents.json`), which it retired
 * (`notebook-retired.json`), and which document replaced each moved one (`notebook-successors.json`).
 * Each file is rewritten whole with one atomic rename. Callers hold the repository's canvas lock
 * for any change.
 *
 * A retired document was replaced by a new one holding its current state (see
 * [NotebookHistoryArchive.compactToNewDocument]). It is never indexed, opened or stored again,
 * whichever peer offers it; its history is kept in the archive.
 */
internal class NotebookDocumentIndex(directory: Path) {
    @Serializable
    private data class Ids(val ids: List<String>)

    private val indexFile = directory.resolve(INDEX_FILE)
    private val retiredFile = directory.resolve(RETIRED_FILE)
    private val successorsFile = directory.resolve(SUCCESSORS_FILE)

    fun read(): List<String> = readIds(indexFile)

    fun retired(): Set<String> = readIds(retiredFile).toSet()

    /** Add [key] at the end; no-op if indexed. */
    fun add(key: String) {
        val previous = read()
        if (key in previous) return
        write(indexFile, IDS, Ids(previous + key))
    }

    /**
     * Put [replacement] where [key] is, in one atomic write: the commit point of a move to a new
     * document. Returns false, changing nothing, if [key] is not indexed. The successor map is
     * written first, and every id that led to [key] now leads to [replacement]; an entry for a key
     * that is still indexed is never consulted, so a crash between the two writes is harmless.
     */
    fun replace(key: String, replacement: String): Boolean {
        val previous = read()
        if (key !in previous) return false
        val successors = successors().mapValues { (_, to) -> if (to == key) replacement else to }
        write(successorsFile, SUCCESSORS, successors + (key to replacement))
        write(indexFile, IDS, Ids(previous.map { if (it == key) replacement else it }.distinct()))
        return true
    }

    /**
     * Each moved document key and the document that replaced it. Empty for moves recorded before
     * this map existed, or when the map is unreadable; an unreadable map is replaced by the next
     * move rather than blocking it, and lookups fall back to scanning archive metadata.
     */
    fun successors(): Map<String, String> = runCatching {
        readFile(successorsFile, SUCCESSORS) { map -> map.all { (from, to) -> from.isNotebookKey() && to.isNotebookKey() && from != to } }
    }.getOrNull().orEmpty()

    /** Record that [key] was retired; no-op if it already was. */
    fun retire(key: String) {
        val previous = readIds(retiredFile)
        if (key in previous) return
        write(retiredFile, IDS, Ids(previous + key))
    }

    private fun readIds(file: Path): List<String> =
        readFile(file, IDS) { (ids) -> ids.all { it.isNotebookKey() } && ids.distinct().size == ids.size }?.ids.orEmpty()

    /** The decoded [file], or null if it does not exist; a malformed or [valid]-rejected file throws. */
    private fun <T> readFile(file: Path, serializer: KSerializer<T>, valid: (T) -> Boolean): T? {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Not a regular notebook index: $file" }
        return Json.decodeFromString(serializer, Files.readString(file, UTF_8)).also {
            require(valid(it)) { "Invalid notebook index: $file" }
        }
    }

    private fun <T> write(file: Path, serializer: KSerializer<T>, value: T) {
        val temp = Files.createTempFile(file.parent, ".notebook-index-", ".tmp")
        try {
            Files.writeString(temp, Json.encodeToString(serializer, value), UTF_8)
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val INDEX_FILE = "notebook-documents.json"
        const val RETIRED_FILE = "notebook-retired.json"
        const val SUCCESSORS_FILE = "notebook-successors.json"
        private val IDS = Ids.serializer()
        private val SUCCESSORS = MapSerializer(String.serializer(), String.serializer())
    }
}

/** A notebook document key: 32 lowercase hex characters. */
internal fun String.isNotebookKey(): Boolean = length == 32 && all { it in '0'..'9' || it in 'a'..'f' }
