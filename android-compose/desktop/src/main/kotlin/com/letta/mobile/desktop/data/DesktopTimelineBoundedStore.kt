package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.StoredImageBodyReference
import com.letta.mobile.data.timeline.snapshot.TimelineImageBodyReader
import com.letta.mobile.data.timeline.snapshot.TimelineImageBodyWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Shared owner supplies continuation serialization; Desktop never interprets evidence or messages. */
internal interface DesktopTimelineCheckpointCodec {
    fun encode(value: TimelineDurableCheckpoint): ByteArray
    fun decode(bytes: ByteArray): TimelineDurableCheckpoint
}

/**
 * One scope per directory, indexed by [DesktopPersistentTimelineAccess] under `v2`, behind a scoped
 * OS transaction lock.
 *
 * Image bodies too large for an event record live content-addressed in the scope's `images`
 * directory, so they go wherever the scope goes. Like Room's ledger they are never reclaimed on
 * replacement or deletion: a body reference can outlive the record that wrote it.
 */
internal class DesktopTimelineBoundedStore(
    private val root: Path,
    private val checkpointCodec: DesktopTimelineCheckpointCodec,
) : TimelineBoundedStore {
    override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
        withContext(Dispatchers.IO) {
            val reader = Reader(directory(scope))
            try { block(reader) } finally { reader.active = false }
        }

    override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            val directory = directory(scope)
            Files.createDirectories(directory)
            FileChannel.open(directory.resolve("transaction.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE).use { channel ->
                channel.tryLock().use { lock ->
                    check(lock != null) { "Another scoped transaction is active" }
                    val tx = Transaction(directory)
                    try {
                        val result = block(tx)
                        currentCoroutineContext().ensureActive()
                        tx.commit()
                        result
                    } finally {
                        tx.active = false
                        // A rolled-back transaction leaves no body behind for a reference to find.
                        tx.discardStagedImages()
                    }
                }
            }
        }

    private fun directory(scope: TimelineScope): Path = root.resolve(digest(
        kotlinx.serialization.json.Json.encodeToString(TimelineScope.serializer(), scope).exactBytes(),
    ))

    private open inner class Reader(val directory: Path) : TimelineStoreReader, TimelineImageBodyReader {
        val persistent = DesktopPersistentTimelineAccess(directory.resolve("v2"), checkpointCodec)
        var active = true
        fun checkActive() { check(active) { "Storage callback has escaped" } }
        val imageDirectory: Path get() = directory.resolve(IMAGE_DIRECTORY)
        override suspend fun resolveImage(reference: StoredImageBodyReference): String? {
            checkActive()
            return DesktopTimelineImageBodies.read(
                DesktopTimelineImageBodies.bodyFile(imageDirectory, reference.sha256), reference,
            )
        }
        override suspend fun toolCall(callId: String): TimelineToolIndexEntry? {
            checkActive()
            return persistent.toolCall(callId)
        }
        override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int): List<TimelineToolIndexEntry> {
            checkActive()
            return persistent.unresolvedTools(afterCallId, maxRows)
        }
        override suspend fun toolSweepGeneration(): Long {
            checkActive()
            return persistent.toolSweepGeneration()
        }
        override suspend fun checkpoint(): TimelineDurableCheckpoint {
            checkActive()
            return persistent.checkpoint()
        }
        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
            checkActive()
            require(maxRows > 0)
            return persistent.metadata(position, maxRows)
        }
        override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? {
            checkActive()
            return persistent.locate(identity)
        }
        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
            checkActive()
            require(offset in 0..pointer.encodedBytes && maxBytes >= 0)
            return persistent.body(pointer, offset, maxBytes)
        }
        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? {
            checkActive()
            require(maxBytes >= 0)
            return persistent.evidence(key, maxBytes)
        }
    }

    private inner class Transaction(directory: Path) :
        Reader(directory), TimelineStoreTransaction, TimelineImageBodyWriter {
        val records = mutableMapOf<TimelineMessageId, TimelineStoredRecord?>()
        // Bodies are staged on disk, not in memory, and published only when the transaction commits.
        private val stagedImages = linkedMapOf<String, Path>()
        override suspend fun persistImage(base64: String): StoredImageBodyReference {
            checkActive()
            currentCoroutineContext().ensureActive()
            val decoded = DesktopTimelineImageBodies.decode(base64)
            val reference = decoded.reference
            if (resolveImage(reference) == null) {
                // Absent, or present but corrupt: stage a fresh copy that replaces it on commit.
                stagedImages.remove(reference.sha256)?.let(Files::deleteIfExists)
                stagedImages[reference.sha256] = DesktopTimelineImageBodies.stage(imageDirectory, decoded.bytes)
            }
            return reference
        }
        override suspend fun resolveImage(reference: StoredImageBodyReference): String? {
            checkActive()
            val staged = stagedImages[reference.sha256] ?: return super.resolveImage(reference)
            return DesktopTimelineImageBodies.read(staged, reference)
        }
        // Bodies land before any record that names them, so a committed reference never dangles.
        private fun publishStagedImages() {
            val iterator = stagedImages.entries.iterator()
            while (iterator.hasNext()) {
                val (sha256, staged) = iterator.next()
                DesktopTimelineImageBodies.publish(staged, DesktopTimelineImageBodies.bodyFile(imageDirectory, sha256))
                iterator.remove()
            }
        }
        fun discardStagedImages() {
            stagedImages.values.forEach { Files.deleteIfExists(it) }
            stagedImages.clear()
        }
        val evidenceChanges = mutableMapOf<String, ByteArray?>()
        val toolChanges = mutableMapOf<String, TimelineToolIndexEntry>()
        var toolGeneration: Long? = null
        override suspend fun toolCall(callId: String): TimelineToolIndexEntry? {
            checkActive()
            return toolChanges[callId] ?: super.toolCall(callId)
        }
        override suspend fun toolSweepGeneration(): Long {
            checkActive()
            return toolGeneration ?: super.toolSweepGeneration()
        }
        override suspend fun putToolCall(entry: TimelineToolIndexEntry) {
            checkActive()
            DesktopPersistentTimelineAccess.exact(entry.callId)
            entry.owner?.let { DesktopPersistentTimelineAccess.exact(it.value) }
            reserve(64L + entry.callId.length * 2L + (entry.owner?.value?.length ?: 0) * 2L)
            toolChanges[entry.callId] = entry
            changed = true
        }
        override suspend fun setToolSweepGeneration(next: Long) {
            checkActive()
            require(next >= toolSweepGeneration())
            toolGeneration = next
            changed = true
        }
        override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int): List<TimelineToolIndexEntry> {
            checkActive()
            require(maxRows in 1..128)
            // Staged changes are bounded by the transaction's mutation cap. Seek persisted
            // neighbors until enough survive the overlay; never scan resolved history.
            val candidates = mutableListOf<TimelineToolIndexEntry>()
            var after = afterCallId
            while (candidates.size < maxRows) {
                val page = super.unresolvedTools(after, maxRows - candidates.size)
                if (page.isEmpty()) break
                candidates.addAll(page.filterNot { toolChanges.containsKey(it.callId) })
                after = page.last().callId
            }
            return (candidates + toolChanges.values.filter {
                it.owner != null && !it.returned && (afterCallId == null || it.callId > afterCallId)
            }).sortedBy { it.callId }.take(maxRows)
        }
        private var stagedBytes = 0L
        private fun reserve(bytes: Long) {
            require(records.size + evidenceChanges.size + toolChanges.size < 256) { "Mutation count budget exceeded" }
            require(bytes <= 8L * 1024 * 1024 - stagedBytes) { "Mutation batch byte budget exceeded" }
            stagedBytes += bytes
        }
        var changed = false
        var revision: Long? = null
        var cursorValue: Pair<TimelineContinuation?, Boolean>? = null
        override suspend fun nextRevision(): Long {
            checkActive()
            check(revision == null) { "Revision requested twice" }
            return Math.addExact(super.checkpoint().revision, 1).also { revision = it }
        }
        override suspend fun put(record: TimelineStoredRecord) {
            checkActive()
            require(record.key.identity.value.isNotEmpty())
            DesktopPersistentTimelineAccess.exact(record.key.identity.value)
            require(record.contentType.length <= 300)
            reserve(record.body.size.toLong() + record.key.identity.value.length * 2L + record.contentType.length * 2L + 64)
            records[record.key.identity] = record.copy(body = record.body.copyOf())
            changed = true
        }
        override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) {
            checkActive(); reserve(identity.value.length * 2L + 64); records[identity] = null; changed = true
        }
        override suspend fun putEvidence(key: String, value: ByteArray) {
            checkActive()
            DesktopPersistentTimelineAccess.exact(key)
            reserve(value.size.toLong() + key.length * 2L + 64)
            evidenceChanges[key] = value.copyOf(); changed = true
        }
        override suspend fun deleteEvidence(key: String) {
            checkActive(); reserve(key.length * 2L + 64); evidenceChanges[key] = null; changed = true
        }
        override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) {
            checkActive(); cursorValue = continuation to hasMore; changed = true
        }
        override suspend fun checkpoint(): TimelineDurableCheckpoint {
            val old = super.checkpoint()
            return TimelineDurableCheckpoint(revision ?: old.revision,
                cursorValue?.first ?: if (cursorValue == null) old.continuation else null,
                cursorValue?.second ?: old.hasMore)
        }
        override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? {
            checkActive()
            return if (records.containsKey(identity)) records[identity]?.key else super.locate(identity)
        }
        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? {
            checkActive(); require(maxBytes >= 0)
            if (!evidenceChanges.containsKey(key)) return super.evidence(key, maxBytes)
            return evidenceChanges[key]?.also { require(it.size <= maxBytes) }?.copyOf()
        }
        private val pendingPointers = mutableMapOf<String, ByteArray>()
        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
            checkActive()
            val bytes = pendingPointers[pointer.value] ?: return super.body(pointer, offset, maxBytes)
            require(pointer.encodedBytes == bytes.size.toLong())
            require(offset in 0..bytes.size.toLong() && maxBytes >= 0)
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + maxBytes).toInt())
        }
        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
            checkActive(); require(maxRows > 0)
            if (records.isEmpty()) return super.metadata(position, maxRows)
            val stamp = revision ?: Math.addExact(super.checkpoint().revision, 1)
            val pending = records.values.filterNotNull().map { record ->
                val token = "pending:" + java.util.UUID.randomUUID()
                pendingPointers[token] = record.body
                TimelineLedgerMetadata(record.key, TimelineBodyPointer(token, record.body.size.toLong()), record.contentType, stamp)
            }
            // Each staged identity can hide at most one persisted row. Fetch enough neighbors
            // on both sides for around windows without walking the persisted history.
            val candidateBudget = Math.multiplyExact(Math.addExact(maxRows, records.size), 2)
            val persisted = super.metadata(position, candidateBudget)
            val rows = (persisted.rows.filterNot { records.containsKey(it.key.identity) } + pending).sortedBy { it.key }
            val selected = java.util.ArrayDeque<TimelineLedgerMetadata>()
            var older = persisted.older != null
            var newer = persisted.newer != null
            val context = currentCoroutineContext()
            for (row in rows) {
                context.ensureActive()
                val accept = when (position) {
                    TimelineReadPosition.Tail -> true
                    is TimelineReadPosition.Before -> row.key < position.key
                    is TimelineReadPosition.After -> row.key > position.key
                    is TimelineReadPosition.Around -> row.key >= position.key
                }
                if (!accept) {
                    if (position is TimelineReadPosition.Around) {
                        selected.addLast(row)
                        if (selected.size > maxRows / 2) { selected.removeFirst(); older = true }
                    } else if (position is TimelineReadPosition.Before) newer = true else older = true
                    continue
                }
                if (selected.size == maxRows) {
                    if (position is TimelineReadPosition.After || position is TimelineReadPosition.Around) {
                        newer = true; break
                    }
                    selected.removeFirst(); older = true
                }
                selected.addLast(row)
            }
            return TimelineMetadataPage(selected.toList(), selected.peekFirst()?.key?.takeIf { older },
                selected.peekLast()?.key?.takeIf { newer }, stamp)
        }
        suspend fun commit() {
            publishStagedImages()
            if (!changed) { check(revision == null); return }
            requireNotNull(revision) { "Changed transaction needs nextRevision" }
            persistent.commit(records, evidenceChanges, checkpoint(), tools = toolChanges,
                toolGeneration = toolGeneration ?: persistent.toolSweepGeneration())
        }
    }

    companion object {
        private const val IMAGE_DIRECTORY = "images"
        // Preserve Kotlin String identity, including unpaired UTF-16 code units.
        private fun String.exactBytes(): ByteArray = java.nio.ByteBuffer.allocate(Math.multiplyExact(length, 2)).also {
            for (char in this) it.putChar(char)
        }.array()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
