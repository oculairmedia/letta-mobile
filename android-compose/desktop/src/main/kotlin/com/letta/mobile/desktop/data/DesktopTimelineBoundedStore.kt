package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.RandomAccessFile
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

/** Immutable generations plus scoped OS transaction locks. No legacy snapshot directory is modified. */
internal class DesktopTimelineBoundedStore(
    private val root: Path,
    private val checkpointCodec: DesktopTimelineCheckpointCodec,
    private val persistentIndexForNewScopes: Boolean = true,
) : TimelineBoundedStore {
    override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
        withContext(Dispatchers.IO) {
            val directory = directory(scope)
            val reader = Reader(directory, legacySnapshot(directory))
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
                    val files = DesktopIndexedTimelineFiles(directory)
                    val tx = Transaction(directory, legacySnapshot(directory))
                    try {
                        val result = block(tx)
                        currentCoroutineContext().ensureActive()
                        tx.commit(files)
                        result
                    } finally { tx.active = false }
                }
            }
        }

    data class MigrationProgress(val source: String, val copiedRows: Long, val totalRows: Long)

    /** Resumable row phase only. The shadow head is never a readable production head. */
    suspend fun migrateRows(scope: TimelineScope, maxRows: Int = 100, maxBodyBytes: Int = 2 * 1024 * 1024): MigrationProgress =
        withContext(Dispatchers.IO) {
            require(maxRows in 1..100 && maxBodyBytes in 1..2 * 1024 * 1024)
            val directory = directory(scope)
            Files.createDirectories(directory)
            FileChannel.open(directory.resolve("transaction.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE).use { channel ->
                channel.tryLock().use { lock ->
                    check(lock != null) { "Another scoped transaction is active" }
                    check(!Files.exists(directory.resolve("v2/active-v2"))) { "Already activated" }
                    val source = requireNotNull(DesktopIndexedTimelineFiles(directory).open())
                    val reader = Reader(directory, source)
                    try {
                        val progressCodec = object : DesktopTimelineCheckpointCodec {
                            override fun encode(value: TimelineDurableCheckpoint): ByteArray =
                                java.io.ByteArrayOutputStream().also { output ->
                                    java.io.DataOutputStream(output).use {
                                        it.writeUTF(source.generation); it.writeLong(source.count); it.writeLong(value.revision)
                                    }
                                }.toByteArray()
                            override fun decode(bytes: ByteArray): TimelineDurableCheckpoint =
                                java.io.DataInputStream(ByteArrayInputStream(bytes)).use {
                                    check(it.readUTF() == source.generation && it.readLong() == source.count) {
                                        "Migration source changed; shadow cannot be activated"
                                    }
                                    val copied = it.readLong()
                                    require(copied in 0..source.count && it.available() == 0)
                                    TimelineDurableCheckpoint(copied, null, true)
                                }
                        }
                        val target = DesktopPersistentTimelineAccess(directory.resolve("migration-v2"), progressCodec)
                        var copied = target.checkpoint().revision
                        val records = linkedMapOf<TimelineMessageId, TimelineStoredRecord?>()
                        val originalRevisions = linkedMapOf<TimelineMessageId, Long>()
                        var remaining = maxBodyBytes.toLong()
                        while (copied < source.count && records.size < maxRows) {
                            currentCoroutineContext().ensureActive()
                            val row = source.row(copied)
                            if (row.bodyBytes > remaining) {
                                require(records.isNotEmpty()) { "Migration body exceeds batch budget" }
                                break
                            }
                            val metadata = java.io.DataInputStream(ByteArrayInputStream(row.bytes))
                            val originalRevision = metadata.readLong()
                            val contentType = metadata.readUTF()
                            val key = row.key.shared()
                            originalRevisions[key.identity] = originalRevision
                            records[key.identity] = TimelineStoredRecord(key, contentType,
                                reader.body(reader.pointer(row), 0, row.bodyBytes.toInt()))
                            remaining -= row.bodyBytes
                            copied++
                        }
                        target.commit(records, emptyMap(), TimelineDurableCheckpoint(copied, null, true), originalRevisions)
                        val verified = DesktopPersistentTimelineAccess(directory.resolve("migration-v2"), progressCodec)
                        for ((identity, record) in records) {
                            val expected = requireNotNull(record)
                            check(verified.locate(identity) == expected.key) { "Migration identity mismatch" }
                            val actual = verified.metadata(TimelineReadPosition.Around(expected.key), 1).rows.single()
                            check(actual.key == expected.key && actual.revision == originalRevisions[identity])
                            check(actual.contentType == expected.contentType)
                            check(verified.body(actual.body, 0, expected.body.size).contentEquals(expected.body)) {
                                "Migration body checksum mismatch"
                            }
                        }
                        MigrationProgress(source.generation, copied, source.count)
                    } finally { reader.active = false }
                }
            }
        }

    // Once v2 is authoritative, even a damaged old manifest must not enter its startup path.
    private fun legacySnapshot(directory: Path): DesktopIndexedTimelineFiles.Snapshot? =
        if (Files.exists(directory.resolve("v2/active-v2"))) null
        else DesktopIndexedTimelineFiles(directory).open()

    private fun directory(scope: TimelineScope): Path = root.resolve(digest(
        kotlinx.serialization.json.Json.encodeToString(TimelineScope.serializer(), scope).exactBytes(),
    ))

    private open inner class Reader(
        val directory: Path,
        val snapshot: DesktopIndexedTimelineFiles.Snapshot?,
    ) : TimelineStoreReader {
        // Existing v1 scopes remain readable/writable without an implicit unbounded migration.
        // Once v2 exists it is authoritative; corrupt v2 must never resurrect a stale v1 head.
        val persistent = if (Files.exists(directory.resolve("v2/active-v2")) ||
            (persistentIndexForNewScopes && snapshot == null)) {
            DesktopPersistentTimelineAccess(directory.resolve("v2"), checkpointCodec)
        } else null
        private val pointerSecret = MessageDigest.getInstance("SHA-256").digest(
            (directory.toAbsolutePath().normalize().toString() + ":" + snapshot?.generation).exactBytes(),
        )
        private fun pointerTag(ordinal: Long): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(pointerSecret, "HmacSHA256"))
            return mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(ordinal).array())
                .joinToString("") { "%02x".format(it) }
        }
        fun pointer(row: DesktopIndexedTimelineFiles.Metadata) = TimelineBodyPointer(
            "${requireNotNull(snapshot).generation}:${row.ordinal}:${pointerTag(row.ordinal)}", row.bodyBytes,
        )
        var active = true
        fun checkActive() { check(active) { "Storage callback has escaped" } }
        fun generationAuxiliary(): Path = directory.resolve(requireNotNull(snapshot).generation + ".aux")
        fun auxiliary(): Path {
            val own = generationAuxiliary()
            val reference = own.resolve("data-generation")
            if (!Files.exists(reference)) return own
            val generation = readBounded(reference, 72).toString(Charsets.US_ASCII)
            require(java.util.UUID.fromString(generation).toString() == generation)
            return directory.resolve("$generation.aux")
        }
        override suspend fun toolCall(callId: String): TimelineToolIndexEntry? {
            checkActive()
            return requireNotNull(persistent) { "Tool index requires migrated persistent storage" }.toolCall(callId)
        }
        override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int): List<TimelineToolIndexEntry> {
            checkActive()
            return requireNotNull(persistent) { "Tool index requires migrated persistent storage" }
                .unresolvedTools(afterCallId, maxRows)
        }
        override suspend fun toolSweepGeneration(): Long {
            checkActive()
            return requireNotNull(persistent) { "Tool index requires migrated persistent storage" }.toolSweepGeneration()
        }
        override suspend fun checkpoint(): TimelineDurableCheckpoint {
            checkActive()
            persistent?.let { return it.checkpoint() }
            if (snapshot == null) return TimelineDurableCheckpoint(0, null, true)
            return checkpointCodec.decode(readBounded(generationAuxiliary().resolve("checkpoint"), MAX_CHECKPOINT))
        }
        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
            checkActive()
            require(maxRows > 0)
            persistent?.let { return it.metadata(position, maxRows) }
            val revision = checkpoint().revision
            val snap = snapshot ?: return TimelineMetadataPage(emptyList(), null, null, revision)
            fun before(key: TimelinePageKey) = snap.seek(key.disk(), 1, DesktopIndexedTimelineFiles.Direction.BEFORE)
                .lastOrNull()?.ordinal?.plus(1) ?: 0L
            val bounds = when (position) {
                TimelineReadPosition.Tail -> (snap.count - maxRows).coerceAtLeast(0) to snap.count
                is TimelineReadPosition.Before -> before(position.key).let { (it - maxRows).coerceAtLeast(0) to it }
                is TimelineReadPosition.After -> {
                    val start = snap.seek(position.key.disk(), 1, DesktopIndexedTimelineFiles.Direction.AFTER)
                        .firstOrNull()?.ordinal ?: snap.count
                    start to minOf(start + maxRows, snap.count)
                }
                is TimelineReadPosition.Around -> {
                    val start = (before(position.key) - maxRows / 2).coerceAtLeast(0)
                        .coerceAtMost((snap.count - maxRows).coerceAtLeast(0))
                    start to minOf(start + maxRows, snap.count)
                }
            }
            val context = currentCoroutineContext()
            val rows = (bounds.first until bounds.second).map { context.ensureActive(); snap.row(it) }
            val mapped = rows.map { row ->
                val metadata = java.io.DataInputStream(ByteArrayInputStream(row.bytes))
                val stamp = metadata.readLong()
                val type = metadata.readUTF()
                TimelineLedgerMetadata(row.key.shared(), pointer(row), type, stamp)
            }
            return TimelineMetadataPage(mapped,
                rows.firstOrNull()?.takeIf { it.ordinal > 0 }?.key?.shared(),
                rows.lastOrNull()?.takeIf { it.ordinal + 1 < snap.count }?.key?.shared(), revision)
        }
        override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? {
            checkActive()
            persistent?.let { return it.locate(identity) }
            if (snapshot == null) return null
            val path = auxiliary().resolve("id-" + digest(identity.value.exactBytes()))
            if (!Files.exists(path)) return null
            val ordinal = java.io.DataInputStream(ByteArrayInputStream(readBounded(path, 8))).readLong()
            val row = snapshot.row(ordinal)
            require(row.key.identity == identity.value) { "Identity index mismatch" }
            return row.key.shared()
        }
        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
            checkActive()
            require(offset in 0..pointer.encodedBytes && maxBytes >= 0)
            persistent?.let { return it.body(pointer, offset, maxBytes) }
            val snap = requireNotNull(snapshot)
            val parts = pointer.value.split(':')
            require(parts.size == 3 && parts[0] == snap.generation) { "Stale generation pointer" }
            val ordinal = parts[1].toLong()
            require(java.security.MessageDigest.isEqual(parts[2].toByteArray(Charsets.US_ASCII),
                pointerTag(ordinal).toByteArray(Charsets.US_ASCII))) { "Unissued body pointer" }
            val row = snap.row(ordinal)
            require(row.bodyBytes == pointer.encodedBytes)
            if (maxBytes == 0) return ByteArray(0)
            val context = currentCoroutineContext()
            val size = minOf(maxBytes.toLong(), row.bodyBytes - offset).toInt()
            val bytes = ByteArray(size)
            var copied = 0
            while (copied < size) {
                val chunk = snap.readBody(row, offset + copied, minOf(MAX_BODY, size - copied)) { context.ensureActive() }
                chunk.copyInto(bytes, copied); copied += chunk.size
            }
            return bytes
        }
        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? {
            checkActive()
            require(maxBytes >= 0)
            persistent?.let { return it.evidence(key, maxBytes) }
            if (snapshot == null) return null
            val path = auxiliary().resolve("e-" + digest(key.exactBytes()))
            if (!Files.exists(path)) return null
            val keyBytes = key.exactBytes()
            val budget = Math.addExact(maxBytes, Math.addExact(keyBytes.size, 4))
            val bytes = readBounded(path, budget)
            java.io.DataInputStream(ByteArrayInputStream(bytes)).use { file ->
                require(file.readInt() == keyBytes.size)
                val stored = ByteArray(keyBytes.size).also(file::readFully)
                require(stored.contentEquals(keyBytes)) { "Evidence index mismatch" }
                return ByteArray(file.available()).also(file::readFully)
            }
        }
    }

    private inner class Transaction(directory: Path, snapshot: DesktopIndexedTimelineFiles.Snapshot?) :
        Reader(directory, snapshot), TimelineStoreTransaction {
        val records = mutableMapOf<TimelineMessageId, TimelineStoredRecord?>()
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
            requireNotNull(persistent) { "Tool index requires migrated persistent storage" }
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
            if (persistent == null) return
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
            record.key.disk()
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
            if (persistent != null) DesktopPersistentTimelineAccess.exact(key)
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
        suspend fun commit(files: DesktopIndexedTimelineFiles) {
            if (!changed) { check(revision == null); return }
            val stamp = requireNotNull(revision) { "Changed transaction needs nextRevision" }
            persistent?.let {
                it.commit(records, evidenceChanges, checkpoint(), tools = toolChanges,
                    toolGeneration = toolGeneration ?: it.toolSweepGeneration())
                return
            }
            val checkpointBytes = checkpointCodec.encode(checkpoint()).copyOf()
            require(checkpointBytes.size <= MAX_CHECKPOINT)
            val context = currentCoroutineContext()
            // Cursor-only revisions share immutable data directly, never a chain of revision overlays.
            if (snapshot != null && records.isEmpty() && evidenceChanges.isEmpty()) {
                val dataGeneration = auxiliary().fileName.toString().removeSuffix(".aux")
                files.publish(emptySequence(), reuse = snapshot, checkpoint = { context.ensureActive() },
                    prepareGeneration = { generation ->
                        val aux = directory.resolve("$generation.aux")
                        Files.createDirectory(aux)
                        writeSynced(aux.resolve("data-generation")) { write(dataGeneration.toByteArray(Charsets.US_ASCII)) }
                        writeSynced(aux.resolve("checkpoint")) { write(checkpointBytes) }
                        FileChannel.open(aux, StandardOpenOption.READ).use { it.force(true) }
                    })
                return
            }
            val entries = sequence {
                val incoming = records.values.filterNotNull().sortedBy { it.key }.iterator()
                var next = if (incoming.hasNext()) incoming.next() else null
                for (ordinal in 0 until (snapshot?.count ?: 0)) {
                    context.ensureActive()
                    val row = requireNotNull(snapshot).row(ordinal)
                    if (records.containsKey(TimelineMessageId(row.key.identity))) continue
                    while (next != null && next.key.disk() < row.key) {
                        yield(entry(next, stamp)); next = if (incoming.hasNext()) incoming.next() else null
                    }
                    yield(DesktopIndexedTimelineFiles.Entry(row.key, row.bytes, row.bodyBytes) {
                        chunkStream(requireNotNull(snapshot), row)
                    })
                }
                while (next != null) {
                    yield(entry(next, stamp)); next = if (incoming.hasNext()) incoming.next() else null
                }
            }
            files.publish(entries, checkpoint = { context.ensureActive() }, prepareGeneration = { generation ->
                val aux = directory.resolve("$generation.aux")
                Files.createDirectory(aux)
                if (snapshot != null) Files.newDirectoryStream(auxiliary(), "e-*").use { paths ->
                    for (path in paths) { context.ensureActive(); Files.createLink(aux.resolve(path.fileName), path) }
                }
                for ((key, value) in evidenceChanges) {
                    context.ensureActive()
                    val path = aux.resolve("e-" + digest(key.exactBytes()))
                    Files.deleteIfExists(path)
                    if (value != null) writeSynced(path) {
                        val bytes = key.exactBytes()
                        writeInt(bytes.size); write(bytes); write(value)
                    }
                }
                // Index identities without allocating or decoding the historical bodies.
                var ordinal = 0L
                for (entry in entries) {
                    context.ensureActive()
                    val path = aux.resolve("id-" + digest(entry.key.identity.exactBytes()))
                    require(!Files.exists(path)) { "Duplicate identity" }
                    writeSynced(path) { writeLong(ordinal) }; ordinal++
                }
                writeSynced(aux.resolve("checkpoint")) { write(checkpointBytes) }
                FileChannel.open(aux, StandardOpenOption.READ).use { it.force(true) }
            })
        }
    }

    private fun entry(record: TimelineStoredRecord, revision: Long): DesktopIndexedTimelineFiles.Entry {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { it.writeLong(revision); it.writeUTF(record.contentType) }
        return DesktopIndexedTimelineFiles.Entry(record.key.disk(), bytes.toByteArray(), record.body.size.toLong()) {
            ByteArrayInputStream(record.body)
        }
    }
    private fun chunkStream(snapshot: DesktopIndexedTimelineFiles.Snapshot, row: DesktopIndexedTimelineFiles.Metadata) =
        object : InputStream() {
            var offset = 0L
            override fun read(): Int {
                if (offset == row.bodyBytes) return -1
                return snapshot.readBody(row, offset++, 1).single().toInt() and 255
            }
            override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                if (length == 0) return 0
                if (offset == row.bodyBytes) return -1
                val part = snapshot.readBody(row, offset, minOf(length, MAX_BODY))
                part.copyInto(bytes, start); offset += part.size
                return part.size
            }
        }

    companion object {
        private const val MAX_BODY = 1024 * 1024
        private const val MAX_CHECKPOINT = 1024 * 1024
        private fun TimelinePageKey.disk() = DesktopIndexedTimelineFiles.Key(order, identity.value)
        private fun DesktopIndexedTimelineFiles.Key.shared() = TimelinePageKey(order, TimelineMessageId(identity))
        // Preserve Kotlin String identity, including unpaired UTF-16 code units.
        private fun String.exactBytes(): ByteArray = java.nio.ByteBuffer.allocate(Math.multiplyExact(length, 2)).also {
            for (char in this) it.putChar(char)
        }.array()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun readBounded(path: Path, maxBytes: Int): ByteArray = RandomAccessFile(path.toFile(), "r").use {
            val size = it.length() - 32
            require(size in 0..maxBytes.toLong()) { "Stored value exceeds budget" }
            val bytes = ByteArray(size.toInt()).also(it::readFully)
            val hash = ByteArray(32).also(it::readFully)
            require(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(bytes))) {
                "Auxiliary index checksum mismatch"
            }
            bytes
        }
        private fun writeSynced(path: Path, block: RandomAccessFile.() -> Unit) {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.block()
                val size = file.filePointer
                file.seek(0)
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                    file.readFully(buffer, 0, count); hash.update(buffer, 0, count); remaining -= count
                }
                file.write(hash.digest()); file.fd.sync()
            }
        }
    }
}
