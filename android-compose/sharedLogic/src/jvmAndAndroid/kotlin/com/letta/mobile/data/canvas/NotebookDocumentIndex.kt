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
 * Which notebook documents a repository holds (`notebook-documents.json`), and which it retired
 * (`notebook-retired.json`). Both are lists of document keys, each rewritten whole with one atomic
 * rename. Callers hold the repository's canvas lock for any change.
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

    /** Record that [key] was retired; no-op if it already was. */
    fun retire(key: String) {
        val previous = readIds(retiredFile)
        if (key in previous) return
        write(retiredFile, previous + key)
    }

    private fun readIds(file: Path): List<String> {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Not a regular notebook index: $file" }
        val ids = Json.decodeFromString<Ids>(Files.readString(file, UTF_8)).ids
        require(ids.all(::isKey) && ids.distinct().size == ids.size) { "Invalid notebook document index: $file" }
        return ids
    }

    private fun write(file: Path, ids: List<String>) {
        val temp = Files.createTempFile(file.parent, ".notebook-index-", ".tmp")
        try {
            Files.writeString(temp, Json.encodeToString(Ids.serializer(), Ids(ids)), UTF_8)
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val INDEX_FILE = "notebook-documents.json"
        const val RETIRED_FILE = "notebook-retired.json"

        fun isKey(key: String): Boolean = key.length == 32 && key.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
