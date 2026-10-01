package com.letta.mobile.data.canvas

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
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
 * [warnBytes] the store logs a warning. Past [maxDocumentBytes], when the store opens, the full
 * history is archived to a compressed cold file and the document continues from a fresh history
 * holding its current state, under the same document id and canvas identity
 * ([NotebookHistoryArchive.compact]).
 */
data class NotebookHistoryBudget(
    val maxDocumentBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
    val warnFraction: Double = DEFAULT_WARN_FRACTION,
    /**
     * Archive and restart an over-budget document when the store opens. Turn it off for a
     * repository that syncs documents with peers: a fresh history under the same id would meet
     * the peers' old history as concurrent edits.
     */
    val compactOversized: Boolean = true,
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
 * open: the store does it before it loads its repository.
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
 */
internal class NotebookHistoryArchive(private val storageRoot: Path) {
    val archiveRoot: Path = storageRoot.resolve(ARCHIVE_DIRECTORY)

    data class Compaction(val archive: Path, val bytesBefore: Long, val bytesAfter: Long)

    /** Where the repository's file storage keeps [documentKey]'s chunks. */
    fun documentDirectory(documentKey: String): Path {
        val name = base58Check(hexBytes(documentKey))
        return storageRoot.resolve(name.take(2)).resolve(name.drop(2))
    }

    /** The repository's storage key for [documentKey]. */
    fun storageName(documentKey: String): String = base58Check(hexBytes(documentKey))

    fun documentBytes(documentKey: String): Long = chunks(documentDirectory(documentKey)).sumOf { Files.size(it) }

    /**
     * Finish or undo a compaction a crash interrupted. The old chunks are moved aside before the
     * fresh snapshot is moved in, so a document directory with files is always whole.
     */
    fun recoverInterrupted(documentKey: String) {
        val directory = archiveRoot.resolve(documentKey)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return
        val replaced = Files.list(directory).use { stream -> stream.iterator().asSequence().filter { it.name.endsWith(REPLACED_SUFFIX) }.toList() }
        val target = documentDirectory(documentKey)
        for (staged in replaced.sorted()) {
            if (chunks(target).isEmpty()) {
                Files.createDirectories(target.parent)
                Files.deleteIfExists(target)
                Files.move(staged, target, ATOMIC_MOVE)
            } else {
                deleteTree(staged)
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

        // Move the old chunks aside, then put the fresh snapshot in their place.
        val staged = archiveDirectory.resolve("$nowEpochMs$REPLACED_SUFFIX")
        Files.move(directory, staged, ATOMIC_MOVE)
        val snapshot = Files.createDirectories(directory.resolve("snapshot")).resolve(sha256Hex(freshBytes))
        val temp = Files.createTempFile(archiveDirectory, ".snapshot-", ".tmp")
        Files.write(temp, freshBytes)
        Files.move(temp, snapshot, ATOMIC_MOVE)
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
