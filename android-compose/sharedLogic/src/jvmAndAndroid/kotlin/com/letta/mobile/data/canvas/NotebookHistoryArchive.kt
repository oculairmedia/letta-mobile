package com.letta.mobile.data.canvas

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Date
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.name
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.Read
import org.automerge.Transaction

/**
 * A size budget for one notebook document's Automerge history.
 *
 * Normal edits keep history small (see [NotebookBoardStorage]); the budget is the safety net. At
 * [warnBytes] the store logs a warning; past [maxDocumentBytes] it raises an
 * [CanvasStorageFault.Kind.OVER_BUDGET] error that the board shows. Only a store that opted in with
 * [compactOversized] then archives the full history to a compressed cold file and continues the
 * document from a fresh history holding its current state, under the same document id and canvas
 * identity ([NotebookHistoryArchive.compact]).
 */
data class NotebookHistoryBudget(
    val maxDocumentBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
    val warnFraction: Double = DEFAULT_WARN_FRACTION,
    /**
     * Archive and restart an over-budget document when the store opens. Off unless asked for: a
     * fresh history under the same id meets any peer's copy of the old history as a concurrent
     * root, and the merge silently loses edits. Only a repository that never syncs may opt in.
     * [NotebookLocalStore.repoForSync] refuses a store that opted in, and a directory that was
     * ever handed to sync is never restarted, whatever a later store asks for.
     */
    val compactOversized: Boolean = false,
) {
    init {
        require(maxDocumentBytes > 0 && warnFraction > 0.0 && warnFraction <= 1.0)
    }

    val warnBytes: Long get() = (maxDocumentBytes * warnFraction).toLong()

    companion object {
        /** A phone's repository copies a document's snapshot about three times while saving it. */
        const val DEFAULT_MAX_DOCUMENT_BYTES: Long = 8L * 1024 * 1024
        const val DEFAULT_WARN_FRACTION: Double = 0.75
    }
}

/**
 * Archives and restarts over-budget documents. Run only while no repository has the document
 * open and while holding the repository's canvas lock: the store does both before its repository
 * may open the document.
 *
 * Layout, beside the repository's own files:
 * ```
 * <storage>/history-archive/<document key>/<epochMs>.automerge.gz
 * ```
 * Each archive is gzip of the document's storage chunks, exactly as they were: gunzip it and pass
 * the bytes to `Document.load` to get the full history up to the moment it was archived. The
 * restarted document lists its archives, oldest first, in `ROOT.historyArchive` (a list of JSON
 * strings: `file`, `bytes`, `heads`, `archivedAtEpochMs`), and its first change holds exactly the
 * state at those heads. A history or undo view walks the chain backwards: the live document's
 * changes, then the newest archive's (whose final state at `heads` is the live document's first
 * state), and so on. Archives are append-only; nothing here deletes them.
 *
 * While a restart runs, the same directory holds `<epochMs>.building` (the fresh document being
 * written), `<epochMs>.fresh` (written whole) and `<epochMs>.replaced` (the old chunks, moved
 * aside); [recoverInterrupted] resolves any combination a crash leaves.
 */
internal class NotebookHistoryArchive(
    private val storageRoot: Path,
    /** Called after each step of [compact]; tests throw from it to stand in for a crash there. */
    private val afterStep: (Step) -> Unit = {},
) {
    val archiveRoot: Path = storageRoot.resolve(ARCHIVE_DIRECTORY)

    data class Compaction(val archive: Path, val bytesBefore: Long, val bytesAfter: Long)

    /** The points a crash can interrupt [compact] at; [recoverInterrupted] handles each. */
    enum class Step {
        /** The cold archive is written; nothing else has changed. */
        ARCHIVED,

        /** The fresh snapshot is written into `<stamp>.building`, which is not yet renamed. */
        FRESH_PARTIAL,

        /** The fresh document directory is whole, as `<stamp>.fresh`. */
        FRESH_READY,

        /** The old chunks are moved aside to `<stamp>.replaced`; the document directory is gone. */
        MOVED_ASIDE,

        /** The fresh directory is in place; the old chunks still wait to be deleted. */
        SWAPPED,
    }

    /** Where the repository's file storage keeps [documentKey]'s chunks. */
    fun documentDirectory(documentKey: String): Path {
        val name = base58Check(hexBytes(documentKey))
        return storageRoot.resolve(name.take(2)).resolve(name.drop(2))
    }

    /** The repository's storage key for [documentKey]. */
    fun storageName(documentKey: String): String = base58Check(hexBytes(documentKey))

    fun documentBytes(documentKey: String): Long = chunks(documentDirectory(documentKey)).sumOf { Files.size(it) }

    /**
     * Finish or undo a restart a crash interrupted, from whichever step it stopped at. The fresh
     * document is built whole beside the old one and moved in with one atomic rename, so the
     * document directory is always the whole old history, the whole fresh snapshot, or absent
     * (with the old history waiting in `<stamp>.replaced`). Directories are deleted recursively,
     * so an empty or partial document directory (which older builds could leave) never blocks a
     * restore.
     */
    fun recoverInterrupted(documentKey: String) {
        val directory = archiveRoot.resolve(documentKey)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return
        val entries = Files.list(directory).use { stream -> stream.iterator().asSequence().toList() }
        // Half-built fresh documents, and the temp files of older builds' restarts.
        entries.filter { it.name.endsWith(BUILDING_SUFFIX) || (it.name.startsWith(".") && it.name.endsWith(".tmp")) }
            .forEach(::deleteTree)
        val stamps = entries.mapNotNull { entry ->
            when {
                entry.name.endsWith(FRESH_SUFFIX) -> entry.name.removeSuffix(FRESH_SUFFIX)
                entry.name.endsWith(REPLACED_SUFFIX) -> entry.name.removeSuffix(REPLACED_SUFFIX)
                else -> null
            }
        }.distinct().sorted()
        val target = documentDirectory(documentKey)
        for (stamp in stamps) {
            val fresh = directory.resolve("$stamp$FRESH_SUFFIX")
            val replaced = directory.resolve("$stamp$REPLACED_SUFFIX")
            val hasFresh = Files.isDirectory(fresh, NOFOLLOW_LINKS)
            val hasReplaced = Files.isDirectory(replaced, NOFOLLOW_LINKS)
            when {
                hasReplaced && chunks(target).isEmpty() -> {
                    // Moved aside but not replaced (or, from older builds, replaced by an empty
                    // directory): finish with the fresh document if it is whole, else put the old
                    // history back. The target holds no chunk files, so deleting it loses nothing.
                    deleteTree(target)
                    Files.createDirectories(target.parent)
                    Files.move(if (hasFresh) fresh else replaced, target, ATOMIC_MOVE)
                    deleteTree(replaced)
                }
                hasReplaced -> {
                    // The swap happened; only the cleanup was cut short.
                    deleteTree(replaced)
                    deleteTree(fresh)
                }
                hasFresh -> {
                    // Stopped before the old history moved: it is still whole where it was.
                    deleteTree(fresh)
                }
            }
        }
    }

    /** Archive [documentKey]'s whole history and restart it from its current state. */
    fun compact(documentKey: String, nowEpochMs: Long): Compaction {
        val directory = documentDirectory(documentKey)
        val files = chunks(directory)
        require(files.isNotEmpty()) { "No stored chunks for $documentKey" }
        // One exact-size buffer: an over-budget document may be tens of MB on a small heap.
        val sizes = files.map { Files.size(it) }
        val history = ByteArray(Math.toIntExact(sizes.sum()))
        var offset = 0
        files.forEach { file ->
            Files.newInputStream(file).use { input ->
                while (true) {
                    val read = input.read(history, offset, history.size - offset)
                    if (read <= 0) break
                    offset += read
                }
            }
        }
        check(offset == history.size) { "Chunks changed while archiving $documentKey" }
        val archiveDirectory = Files.createDirectories(archiveRoot.resolve(documentKey))
        val archive = archiveDirectory.resolve("$nowEpochMs$ARCHIVE_SUFFIX")
        writeArchive(archive, history)
        afterStep(Step.ARCHIVED)

        val old = Document.load(history)
        val fresh = Document()
        val freshBytes = try {
            val heads = old.heads.map { hex(it.bytes) }
            fresh.startTransaction().use { tx ->
                copyMap(old, ObjectId.ROOT, tx, ObjectId.ROOT)
                appendArchiveEntry(tx, archive.name, history.size.toLong(), heads, nowEpochMs)
                tx.commit()
            }
            fresh.save()
        } finally {
            old.free()
            fresh.free()
        }
        Document.load(freshBytes).free()

        // Build the fresh document directory whole beside the old one, then swap it in with one
        // rename. recoverInterrupted resolves a crash between any two of these steps.
        val building = archiveDirectory.resolve("$nowEpochMs$BUILDING_SUFFIX")
        deleteTree(building)
        val snapshot = Files.createDirectories(building.resolve("snapshot")).resolve(sha256Hex(freshBytes))
        FileChannel.open(snapshot, CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(freshBytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        afterStep(Step.FRESH_PARTIAL)
        val freshDirectory = archiveDirectory.resolve("$nowEpochMs$FRESH_SUFFIX")
        Files.move(building, freshDirectory, ATOMIC_MOVE)
        afterStep(Step.FRESH_READY)
        val staged = archiveDirectory.resolve("$nowEpochMs$REPLACED_SUFFIX")
        Files.move(directory, staged, ATOMIC_MOVE)
        afterStep(Step.MOVED_ASIDE)
        Files.move(freshDirectory, directory, ATOMIC_MOVE)
        afterStep(Step.SWAPPED)
        deleteTree(staged)
        return Compaction(archive, history.size.toLong(), freshBytes.size.toLong())
    }

    private fun writeArchive(archive: Path, history: ByteArray) {
        val temp = Files.createTempFile(archive.parent, ".archive-", ".tmp")
        try {
            GZIPOutputStream(Files.newOutputStream(temp)).use { it.write(history) }
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            GZIPInputStream(Files.newInputStream(temp)).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    length += read
                }
            }
            check(length == history.size.toLong() && digest.digest().contentEquals(sha256(history))) {
                "History archive did not read back"
            }
            Files.move(temp, archive, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun appendArchiveEntry(tx: Transaction, file: String, bytes: Long, heads: List<String>, at: Long) {
        val list = (tx.get(ObjectId.ROOT, HISTORY_ARCHIVE).orElse(null) as? AmValue.List)?.id
            ?: tx.set(ObjectId.ROOT, HISTORY_ARCHIVE, ObjectType.LIST)
        val entry = JsonObject(
            mapOf(
                "file" to JsonPrimitive(file),
                "bytes" to JsonPrimitive(bytes),
                "heads" to JsonArray(heads.map(::JsonPrimitive)),
                "archivedAtEpochMs" to JsonPrimitive(at),
            ),
        )
        tx.insert(list, tx.length(list), entry.toString())
    }

    /** Chunk files, snapshots first, as the repository loads them. */
    private fun chunks(directory: Path): List<Path> {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return emptyList()
        return Files.walk(directory).use { stream ->
            stream.iterator().asSequence().filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && !it.name.endsWith(".tmp") }.toList()
        }.sortedWith(compareBy<Path>({ if (it.parent.name == "snapshot") 0 else 1 }, { it.toString() }))
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { stream -> stream.iterator().asSequence().toList() }.sortedDescending().forEach(Files::delete)
    }

    companion object {
        const val ARCHIVE_DIRECTORY = "history-archive"
        const val HISTORY_ARCHIVE = "historyArchive"
        private const val ARCHIVE_SUFFIX = ".automerge.gz"
        private const val REPLACED_SUFFIX = ".replaced"
        private const val FRESH_SUFFIX = ".fresh"
        private const val BUILDING_SUFFIX = ".building"
        private const val BUFFER_BYTES = 64 * 1024
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** Copy a whole object tree. Text marks are not copied; notebook text carries none. */
        fun copyMap(source: Read, from: ObjectId, tx: Transaction, to: ObjectId) {
            for (key in source.keys(from).orElseThrow()) {
                when (val value = source.get(from, key).orElseThrow()) {
                    is AmValue.Map -> copyMap(source, value.id, tx, tx.set(to, key, ObjectType.MAP))
                    is AmValue.List -> copyList(source, value.id, tx, tx.set(to, key, ObjectType.LIST))
                    is AmValue.Text -> tx.spliceText(tx.set(to, key, ObjectType.TEXT), 0, 0, source.text(value.id).orElseThrow())
                    else -> tx.set(to, key, scalar(value))
                }
            }
        }

        private fun copyList(source: Read, from: ObjectId, tx: Transaction, to: ObjectId) {
            source.listItems(from).orElseThrow().forEachIndexed { index, value ->
                val at = index.toLong()
                when (value) {
                    is AmValue.Map -> copyMap(source, value.id, tx, tx.insert(to, at, ObjectType.MAP))
                    is AmValue.List -> copyList(source, value.id, tx, tx.insert(to, at, ObjectType.LIST))
                    is AmValue.Text -> tx.spliceText(tx.insert(to, at, ObjectType.TEXT), 0, 0, source.text(value.id).orElseThrow())
                    else -> tx.insert(to, at, scalar(value))
                }
            }
        }

        private fun scalar(value: AmValue): NewValue = when (value) {
            is AmValue.Str -> NewValue.str(value.value)
            is AmValue.Int -> NewValue.integer(value.value)
            is AmValue.UInt -> NewValue.uint(value.value)
            is AmValue.F64 -> NewValue.f64(value.value)
            is AmValue.Bool -> NewValue.bool(value.value)
            is AmValue.Bytes -> NewValue.bytes(value.value)
            is AmValue.Counter -> NewValue.counter(value.value)
            is AmValue.Timestamp -> NewValue.timestamp(Date(value.value.time))
            is AmValue.Null -> NewValue.NULL
            // Refuse rather than drop a value this version cannot write back.
            else -> error("Cannot copy Automerge value ${value::class.java.simpleName}")
        }

        fun base58Check(payload: ByteArray): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val check = sha.digest(sha.digest(payload)).copyOf(4)
            val bytes = payload + check
            var number = BigInteger(1, bytes)
            val out = StringBuilder()
            val base = BigInteger.valueOf(ALPHABET.length.toLong())
            while (number.signum() > 0) {
                val (quotient, remainder) = number.divideAndRemainder(base)
                out.append(ALPHABET[remainder.toInt()])
                number = quotient
            }
            bytes.takeWhile { it.toInt() == 0 }.forEach { _ -> out.append(ALPHABET[0]) }
            return out.reverse().toString()
        }

        private fun hexBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))
    }
}
