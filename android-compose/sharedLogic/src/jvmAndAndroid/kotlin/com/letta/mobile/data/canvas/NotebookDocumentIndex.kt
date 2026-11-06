package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which notebook documents a repository holds (`notebook-documents.json`), which it retired
 * (`notebook-retired.json`), and which current document replaced each retired one
 * (`notebook-successors.json`). Each file is rewritten whole with one atomic rename. Callers hold
 * the repository's canvas lock for any change.
 *
 * A retired document was replaced by a new one holding its current state (see
 * [NotebookHistoryArchive.compactToNewDocument]). It is never indexed, opened or stored again,
 * whichever peer offers it; its history is kept in the archive.
 */
internal class NotebookDocumentIndex(directory: Path) {
    @Serializable
    private data class Ids(val ids: List<String>)

    @Serializable
    private data class Successors(val successors: Map<String, String>)

    private val indexFile = directory.resolve(INDEX_FILE)
    private val retiredFile = directory.resolve(RETIRED_FILE)
    private val successorsFile = directory.resolve(SUCCESSORS_FILE)

    fun read(): List<String> = readIds(indexFile)

    fun retired(): Set<String> = readIds(retiredFile).toSet()

    /** Add [key] at the end; no-op if indexed. */
    fun add(key: String) {
        val previous = read()
        if (key in previous) return
        write(indexFile, previous + key)
    }

    /**
     * Put [replacement] where [key] is, in one atomic write: the commit point of a move to a new
     * document. Returns false, changing nothing, if [key] is not indexed.
     */
    fun replace(key: String, replacement: String): Boolean {
        val previous = read()
        if (key !in previous) return false
        write(indexFile, previous.map { if (it == key) replacement else it }.distinct())
        return true
    }

    /**
     * Record that [key] was retired in favor of [successor]. Every id that already led to [key]
     * now leads to [successor] too, so one lookup reaches the current document. The successor map
     * is written first: an entry without its retirement is never consulted.
     */
    fun retire(key: String, successor: String) {
        require(isKey(successor) && successor != key) { "Invalid successor for $key: $successor" }
        val successors = readSuccessors()
        val updated = successors.mapValues { (_, current) -> if (current == key) successor else current } + (key to successor)
        if (updated != successors) writeSuccessors(updated)
        val previous = readIds(retiredFile)
        if (key in previous) return
        write(retiredFile, previous + key)
    }

    /** The current document recorded for retired [key], or null for a store written before this map. */
    fun successorOf(key: String): String? = readSuccessors()[key]

    private fun readSuccessors(): Map<String, String> {
        if (!Files.exists(successorsFile, NOFOLLOW_LINKS)) return emptyMap()
        require(Files.isRegularFile(successorsFile, NOFOLLOW_LINKS)) { "Not a regular notebook index: $successorsFile" }
        val successors = Json.decodeFromString<Successors>(Files.readString(successorsFile, UTF_8)).successors
        require(successors.all { (from, to) -> isKey(from) && isKey(to) && from != to }) {
            "Invalid notebook successor index: $successorsFile"
        }
        return successors
    }

    private fun writeSuccessors(successors: Map<String, String>) =
        replaceFile(successorsFile, Json.encodeToString(Successors.serializer(), Successors(successors)))

    private fun readIds(file: Path): List<String> {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Not a regular notebook index: $file" }
        val ids = Json.decodeFromString<Ids>(Files.readString(file, UTF_8)).ids
        require(ids.all(::isKey) && ids.distinct().size == ids.size) { "Invalid notebook document index: $file" }
        return ids
    }

    private fun write(file: Path, ids: List<String>) = replaceFile(file, Json.encodeToString(Ids.serializer(), Ids(ids)))

    private fun replaceFile(file: Path, content: String) {
        val temp = Files.createTempFile(file.parent, ".notebook-index-", ".tmp")
        try {
            Files.writeString(temp, content, UTF_8)
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val INDEX_FILE = "notebook-documents.json"
        const val RETIRED_FILE = "notebook-retired.json"
        const val SUCCESSORS_FILE = "notebook-successors.json"

        fun isKey(key: String): Boolean = key.length == 32 && key.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
