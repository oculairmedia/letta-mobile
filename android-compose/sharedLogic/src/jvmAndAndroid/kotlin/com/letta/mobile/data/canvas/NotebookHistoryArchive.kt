package com.letta.mobile.data.canvas

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.Read
import org.automerge.Transaction
import org.automerge.repo.DocumentId

/**
 * A size budget for one notebook document's Automerge history.
 *
 * Normal edits keep history small (see [NotebookBoardStorage]); the budget is the safety net. At
 * [warnBytes] the store logs a warning. Past [maxDocumentBytes], the next time the store opens it
 * archives the document's full history to a compressed cold file and moves the board to a new
 * document holding only its current state ([NotebookHistoryArchive.compactToNewDocument]). Until
 * then, and if that move fails, the board shows a [CanvasStorageFault.Kind.OVER_BUDGET] error.
 */
data class NotebookHistoryBudget(
    val maxDocumentBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
    val warnFraction: Double = DEFAULT_WARN_FRACTION,
) {
    init {
        require(maxDocumentBytes > 0 && warnFraction > 0.0 && warnFraction <= 1.0)
    }

    val warnBytes: Long get() = (maxDocumentBytes * warnFraction).toLong()

    /**
     * How much of the deleted-element restore list a new document carries over; the rest is only
     * in the archive. See [NotebookBoardStorage.copyCurrentState].
     */
    val restoreListBytes: Long get() = maxDocumentBytes / RESTORE_LIST_SHARE

    companion object {
        /** A phone's repository copies a document's snapshot about three times while saving it. */
        const val DEFAULT_MAX_DOCUMENT_BYTES: Long = 8L * 1024 * 1024
        const val DEFAULT_WARN_FRACTION: Double = 0.75
        private const val RESTORE_LIST_SHARE = 8
    }
}

/**
 * Moves over-budget documents to new ones. Run only while no repository has the document open and
 * while holding the repository's canvas lock: the store does both before its repository may open
 * any document.
 *
 * A move never restarts a history under the same document id: peers that sync the document would
 * merge their copy of the old history with the fresh one as concurrent roots and lose edits.
 * Instead the board continues in a new document (new id, the same canvas metadata), and the old id
 * is retired here: its storage is deleted and it is never indexed, opened or stored again, whatever
 * a peer offers (see [NotebookDocumentIndex]). Peers keep their own copy of the old document; it is
 * disposable.
 *
 * Layout, beside the repository's own files:
 * ```
 * <storage>/history-archive/<old document key>/<epochMs>.automerge.gz
 * ```
 * Each archive is gzip of the old document's storage chunks, exactly as they were: gunzip it and
 * pass the bytes to `Document.load` to get the full history. The new document lists its archives,
 * oldest first, in `ROOT.historyArchive` (JSON strings: `document`, `file`, `bytes`, `heads`,
 * `archivedAtEpochMs`; the file is `history-archive/<document>/<file>`) and names the document it
 * replaced in `ROOT.previousDocumentId`. Its first change holds the old document's state at
 * `heads`, so a history view walks the chain backwards. Opening a board never reads an archive;
 * only an explicit history or restore view would. Archives are append-only.
 *
 * While a move runs, the old document's archive directory holds `<epochMs>.building` (the new
 * document being written) and then `<epochMs>.successor` (the new document's key); the index swap
 * is the commit point. [recoverInterrupted] resolves whatever a crash leaves.
 */
internal class NotebookHistoryArchive(
    private val storageRoot: Path,
    /** Called after each step of [compactToNewDocument]; tests throw from it to stand in for a crash there. */
    private val afterStep: (Step) -> Unit = {},
) {
    val archiveRoot: Path = storageRoot.resolve(ARCHIVE_DIRECTORY)

    data class Compaction(
        val archive: Path,
        val newKey: String,
        val bytesBefore: Long,
        val bytesAfter: Long,
        val canvasId: CanvasId?,
        /** Deleted-element snapshots left only in the archive. */
        val restoreEntriesArchived: Int,
    )

    /** The points a crash can interrupt [compactToNewDocument] at; [recoverInterrupted] handles each. */
    enum class Step {
        /** The cold archive is written and verified; nothing else has changed. */
        ARCHIVED,

        /** The new document's snapshot is written into `<stamp>.building`, not yet journaled. */
        SUCCESSOR_PARTIAL,

        /** `<stamp>.successor` names the new document; its storage is not in place yet. */
        JOURNALED,

        /** The new document's storage is in place; the index still names the old one. */
        SUCCESSOR_READY,

        /** The index names the new document (the commit point); the old id is not yet retired. */
        INDEXED,

        /** The old id is recorded as retired; its storage is not yet deleted. */
        RETIRED,

        /** The old storage is deleted; only the journal is left. */
        OLD_DELETED,
    }

    /** Where the repository's file storage keeps [documentKey]'s chunks. */
    fun documentDirectory(documentKey: String): Path {
        val name = storageName(documentKey)
        return storageRoot.resolve(name.take(2)).resolve(name.drop(2))
    }

    /** The repository's storage key for [documentKey]. */
    fun storageName(documentKey: String): String = base58Check(hexBytes(documentKey))

    fun documentBytes(documentKey: String): Long = chunks(documentDirectory(documentKey)).sumOf { Files.size(it) }

    /**
     * Finish or undo every move a crash interrupted, whichever step it stopped at: before the index
     * names the new document, the move is undone (unless the new document is already whole, when
     * it is finished); after, it is finished. Also resolves the same-id restarts of earlier builds.
     */
    fun recoverInterrupted(index: NotebookDocumentIndex) {
        if (!Files.isDirectory(archiveRoot, NOFOLLOW_LINKS)) return
        val keys = Files.list(archiveRoot).use { stream -> stream.iterator().asSequence().map { it.name }.toList() }
            .filter(NotebookDocumentIndex::isKey)
        for (key in keys) {
            val directory = archiveRoot.resolve(key)
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) continue
            // Half-built documents (never journaled, or journaled but never moved in), and temp files.
            entries(directory).filter { it.name.endsWith(BUILDING_SUFFIX) || (it.name.startsWith(".") && it.name.endsWith(".tmp")) }
                .forEach(::deleteTree)
            recoverLegacyRestart(key, directory)
            entries(directory).filter { it.name.endsWith(SUCCESSOR_SUFFIX) }.sorted().forEach { journal ->
                val successor = Files.readString(journal, UTF_8).trim()
                val indexed = index.read()
                when {
                    !NotebookDocumentIndex.isKey(successor) -> Files.delete(journal)
                    successor in indexed -> retire(key, journal, index)
                    key in indexed && chunks(documentDirectory(successor)).isNotEmpty() -> {
                        // The new document is whole: finish the move rather than discard it.
                        index.replace(key, successor)
                        retire(key, journal, index)
                    }
                    else -> {
                        // Never committed: the new document, if any of it exists, is referenced by
                        // nothing. The old document is untouched.
                        deleteTree(documentDirectory(successor))
                        Files.delete(journal)
                    }
                }
            }
        }
    }

    /**
     * Archive [documentKey]'s whole history and move its board to a new document holding its
     * current state; returns where. The caller holds the canvas lock and no repository has
     * [documentKey] open.
     */
    fun compactToNewDocument(
        documentKey: String,
        nowEpochMs: Long,
        index: NotebookDocumentIndex,
        restoreListBytes: Long,
    ): Compaction {
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

        val newKey = hex(DocumentId.generate().bytes)
        val old = Document.load(history)
        val fresh = Document()
        val successor = try {
            val heads = old.heads.map { hex(it.bytes) }
            val archived = fresh.startTransaction().use { tx ->
                val archived = NotebookBoardStorage.copyCurrentState(old, tx, setOf(HISTORY_ARCHIVE, PREVIOUS_DOCUMENT), restoreListBytes)
                copyArchiveEntries(old, tx, documentKey)
                appendArchiveEntry(tx, documentKey, archive.name, history.size.toLong(), heads, nowEpochMs)
                tx.set(ObjectId.ROOT, PREVIOUS_DOCUMENT, documentKey)
                tx.commit()
                archived
            }
            // The board, notes, items and canvas identity must read back exactly as they were.
            check(NotebookBoardStorage.sameContent(old, fresh)) { "The new document's content differs from $documentKey's" }
            val canvasId = (fresh.get(ObjectId.ROOT, "canvasMetadata").orElse(null) as? AmValue.Str)?.value
                ?.let { runCatching { Json.decodeFromString<CanvasDocument>(it).id }.getOrNull() }
            Triple(fresh.save(), canvasId, archived)
        } finally {
            old.free()
            fresh.free()
        }
        val (freshBytes, canvasId, archivedEntries) = successor
        Document.load(freshBytes).free()

        // Build the new document whole, journal it, move it in with one rename, then commit by
        // swapping the index. recoverInterrupted resolves a crash between any two of these steps.
        val building = archiveDirectory.resolve("$nowEpochMs$BUILDING_SUFFIX")
        deleteTree(building)
        val snapshot = Files.createDirectories(building.resolve("snapshot")).resolve(sha256Hex(freshBytes))
        FileChannel.open(snapshot, CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(freshBytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        afterStep(Step.SUCCESSOR_PARTIAL)
        val journal = archiveDirectory.resolve("$nowEpochMs$SUCCESSOR_SUFFIX")
        val journalTemp = Files.createTempFile(archiveDirectory, ".successor-", ".tmp")
        try {
            Files.writeString(journalTemp, newKey, UTF_8)
            Files.move(journalTemp, journal, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(journalTemp)
        }
        afterStep(Step.JOURNALED)
        val target = documentDirectory(newKey)
        check(!Files.exists(target, NOFOLLOW_LINKS)) { "New document $newKey already has storage" }
        Files.createDirectories(target.parent)
        Files.move(building, target, ATOMIC_MOVE)
        afterStep(Step.SUCCESSOR_READY)
        check(index.replace(documentKey, newKey)) { "$documentKey left the index during its move" }
        afterStep(Step.INDEXED)
        retire(documentKey, journal, index)
        return Compaction(archive, newKey, history.size.toLong(), freshBytes.size.toLong(), canvasId, archivedEntries)
    }

    /** After the commit point: retire [key], delete its storage, then the journal. */
    private fun retire(key: String, journal: Path, index: NotebookDocumentIndex) {
        index.retire(key)
        afterStep(Step.RETIRED)
        deleteTree(documentDirectory(key))
        afterStep(Step.OLD_DELETED)
        Files.deleteIfExists(journal)
    }

    /**
     * Earlier builds restarted a history in place: `<stamp>.fresh` (the fresh document, whole) and
     * `<stamp>.replaced` (the old chunks, moved aside). The document directory is always the whole
     * old history, the whole fresh snapshot, or absent with the old history in `.replaced`.
     */
    private fun recoverLegacyRestart(documentKey: String, directory: Path) {
        val stamps = entries(directory).mapNotNull { entry ->
            when {
                entry.name.endsWith(LEGACY_FRESH_SUFFIX) -> entry.name.removeSuffix(LEGACY_FRESH_SUFFIX)
                entry.name.endsWith(LEGACY_REPLACED_SUFFIX) -> entry.name.removeSuffix(LEGACY_REPLACED_SUFFIX)
                else -> null
            }
        }.distinct().sorted()
        val target = documentDirectory(documentKey)
        for (stamp in stamps) {
            val fresh = directory.resolve("$stamp$LEGACY_FRESH_SUFFIX")
            val replaced = directory.resolve("$stamp$LEGACY_REPLACED_SUFFIX")
            val hasFresh = Files.isDirectory(fresh, NOFOLLOW_LINKS)
            val hasReplaced = Files.isDirectory(replaced, NOFOLLOW_LINKS)
            when {
                hasReplaced && chunks(target).isEmpty() -> {
                    // Moved aside but not replaced (or replaced by an empty directory): finish with
                    // the fresh document if it is whole, else put the old history back.
                    deleteTree(target)
                    Files.createDirectories(target.parent)
                    Files.move(if (hasFresh) fresh else replaced, target, ATOMIC_MOVE)
                    deleteTree(replaced)
                }
                hasReplaced -> {
                    deleteTree(replaced)
                    deleteTree(fresh)
                }
                hasFresh -> deleteTree(fresh)
            }
        }
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

    /**
     * Carry the old document's own archive list over. Entries written by earlier same-id restarts
     * have no `document`; their files are under the old document's key.
     */
    private fun copyArchiveEntries(old: Read, tx: Transaction, oldKey: String) {
        val previous = (old.get(ObjectId.ROOT, HISTORY_ARCHIVE).orElse(null) as? AmValue.List)?.id ?: return
        val list = tx.set(ObjectId.ROOT, HISTORY_ARCHIVE, ObjectType.LIST)
        old.listItems(previous).orElseThrow().forEachIndexed { at, value ->
            val raw = (value as? AmValue.Str)?.value ?: return@forEachIndexed
            val entry = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val named = if (entry == null || "document" in entry) raw else JsonObject(entry + ("document" to JsonPrimitive(oldKey))).toString()
            tx.insert(list, at.toLong(), named)
        }
    }

    private fun appendArchiveEntry(tx: Transaction, document: String, file: String, bytes: Long, heads: List<String>, at: Long) {
        val list = (tx.get(ObjectId.ROOT, HISTORY_ARCHIVE).orElse(null) as? AmValue.List)?.id
            ?: tx.set(ObjectId.ROOT, HISTORY_ARCHIVE, ObjectType.LIST)
        val entry = JsonObject(
            mapOf(
                "document" to JsonPrimitive(document),
                "file" to JsonPrimitive(file),
                "bytes" to JsonPrimitive(bytes),
                "heads" to JsonArray(heads.map(::JsonPrimitive)),
                "archivedAtEpochMs" to JsonPrimitive(at),
            ),
        )
        tx.insert(list, tx.length(list), entry.toString())
    }

    private fun entries(directory: Path): List<Path> =
        Files.list(directory).use { stream -> stream.iterator().asSequence().toList() }

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
        const val PREVIOUS_DOCUMENT = "previousDocumentId"
        private const val ARCHIVE_SUFFIX = ".automerge.gz"
        private const val SUCCESSOR_SUFFIX = ".successor"
        private const val BUILDING_SUFFIX = ".building"
        private const val LEGACY_REPLACED_SUFFIX = ".replaced"
        private const val LEGACY_FRESH_SUFFIX = ".fresh"
        private const val BUFFER_BYTES = 64 * 1024
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** Copy one value into map [to] at [key]. Text marks are not copied; notebook text carries none. */
        fun copyValue(source: Read, value: AmValue, tx: Transaction, to: ObjectId, key: String) {
            when (value) {
                is AmValue.Map -> copyMap(source, value.id, tx, tx.set(to, key, ObjectType.MAP))
                is AmValue.List -> copyList(source, value.id, tx, tx.set(to, key, ObjectType.LIST))
                is AmValue.Text -> tx.spliceText(tx.set(to, key, ObjectType.TEXT), 0, 0, source.text(value.id).orElseThrow())
                else -> tx.set(to, key, scalar(value))
            }
        }

        /** Copy a whole object tree. */
        fun copyMap(source: Read, from: ObjectId, tx: Transaction, to: ObjectId) {
            for (key in source.keys(from).orElseThrow()) copyValue(source, source.get(from, key).orElseThrow(), tx, to, key)
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
