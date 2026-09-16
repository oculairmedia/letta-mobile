package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Binds opaque shared storage records to independent file indexes, not canonical semantics. */
internal class DesktopPersistentTimelineAccess(directory: Path, private val codec: DesktopTimelineCheckpointCodec) {
    private val index = DesktopPersistentTimelineIndex(directory)
    private val head = index.open()
    private val roots = head?.roots ?: DesktopPersistentTimelineIndex.Roots()
    // Stable across callbacks/restart at this scoped revision for shared deferred body resolution.
    // This detects pointer substitution; it is not an authorization boundary against local code.
    private val secret = java.security.MessageDigest.getInstance("SHA-256").digest(
        directory.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8) +
            (head?.checkpoint ?: byteArrayOf()),
    )
    private fun tag(ref: String, size: Long): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
        return java.util.HexFormat.of().formatHex(mac.doFinal("$ref:$size".toByteArray(Charsets.US_ASCII)))
    }
    private fun budget() = DesktopPersistentTimelineIndex.Budget()
    fun checkpoint() = head?.let { codec.decode(it.checkpoint) } ?: TimelineDurableCheckpoint(0, null, true)

    fun locate(identity: TimelineMessageId): TimelinePageKey? =
        index.get(roots.identity, exact(identity.value), budget())?.let(::decodeKey)

    suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
        require(maxRows in 1..MAX_ROWS)
        val budget = budget()
        val count = index.count(roots.order, budget)
        val start = when (position) {
            TimelineReadPosition.Tail -> (count - maxRows).coerceAtLeast(0)
            is TimelineReadPosition.Before -> (index.rank(roots.order, encodeKey(position.key), budget) - maxRows).coerceAtLeast(0)
            is TimelineReadPosition.After -> {
                val key = encodeKey(position.key)
                index.rank(roots.order, key, budget) + if (index.get(roots.order, key, budget) == null) 0 else 1
            }
            is TimelineReadPosition.Around -> (index.rank(roots.order, encodeKey(position.key), budget) - maxRows / 2)
                .coerceAtLeast(0).coerceAtMost((count - maxRows).coerceAtLeast(0))
        }
        val end = if (position is TimelineReadPosition.Before) index.rank(roots.order, encodeKey(position.key), budget)
            else minOf(count, start + maxRows)
        val rows = (start until end).map { ordinal ->
            currentCoroutineContext().ensureActive()
            val entry = requireNotNull(index.select(roots.order, ordinal, budget))
            DataInputStream(ByteArrayInputStream(entry.value)).use { data ->
                val revision = data.readLong()
                val type = data.readUTF()
                val size = data.readLong()
                val ref = data.readUTF()
                require(data.available() == 0 && size >= 0)
                // Scoped revision tags bind exact object references, never mutable ordinals.
                val token = "v2:$ref:${tag(ref, size)}"
                TimelineLedgerMetadata(decodeKey(entry.key), TimelineBodyPointer(token, size), type, revision)
            }
        }
        return TimelineMetadataPage(rows, rows.firstOrNull()?.key?.takeIf { start > 0 },
            rows.lastOrNull()?.key?.takeIf { end < count }, checkpoint().revision)
    }

    fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
        val parts = pointer.value.split(':')
        require(parts.size == 3 && parts[0] == "v2") { "Unissued body pointer" }
        val ref = parts[1]
        val size = pointer.encodedBytes
        require(java.security.MessageDigest.isEqual(parts[2].toByteArray(Charsets.US_ASCII),
            tag(ref, size).toByteArray(Charsets.US_ASCII))) { "Unissued body pointer" }
        require(offset in 0..size && maxBytes in 0..MAX_BYTES)
        return readBlob(ref, size, offset, maxBytes, budget())
    }

    fun evidence(key: String, maxBytes: Int): ByteArray? {
        require(maxBytes in 0..MAX_BYTES)
        val budget = budget()
        val value = index.get(roots.evidence, exact(key), budget) ?: return null
        val data = DataInputStream(ByteArrayInputStream(value))
        val size = data.readLong()
        val ref = data.readUTF()
        require(size in 0..maxBytes.toLong() && data.available() == 0)
        return readBlob(ref, size, 0, maxBytes, budget)
    }

    fun toolCall(callId: String): TimelineToolIndexEntry? =
        index.get(roots.tools, exact(callId), budget())?.let { decodeTool(callId, it) }

    fun toolSweepGeneration(): Long = roots.toolGeneration

    suspend fun unresolvedTools(afterCallId: String?, maxRows: Int): List<TimelineToolIndexEntry> {
        require(maxRows in 1..128)
        val budget = budget()
        val key = afterCallId?.let(::exact)
        val start = if (key == null) 0L else index.rank(roots.unresolved, key, budget) +
            if (index.get(roots.unresolved, key, budget) == null) 0 else 1
        val end = minOf(index.count(roots.unresolved, budget), start + maxRows)
        return (start until end).map { ordinal ->
            currentCoroutineContext().ensureActive()
            val entry = requireNotNull(index.select(roots.unresolved, ordinal, budget))
            decodeTool(decodeExact(entry.key), entry.value).also { require(it.owner != null && !it.returned) }
        }
    }

    private fun decodeExact(bytes: ByteArray): String {
        require(bytes.size <= 512 && bytes.size % 2 == 0)
        val buffer = ByteBuffer.wrap(bytes)
        return buildString { while (buffer.hasRemaining()) append(buffer.char) }
    }

    private fun decodeTool(callId: String, bytes: ByteArray): TimelineToolIndexEntry =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            val owner = if (it.readBoolean()) {
                val size = it.readInt()
                require(size in 0..512 && size % 2 == 0)
                TimelineMessageId(decodeExact(ByteArray(size).also(it::readFully)))
            } else null
            val returned = it.readBoolean()
            require(it.available() == 0)
            TimelineToolIndexEntry(callId, owner, returned)
        }

    suspend fun commit(records: Map<TimelineMessageId, TimelineStoredRecord?>, evidence: Map<String, ByteArray?>,
        checkpoint: TimelineDurableCheckpoint, originalRevisions: Map<TimelineMessageId, Long> = emptyMap(),
        tools: Map<String, TimelineToolIndexEntry> = emptyMap(), toolGeneration: Long = roots.toolGeneration) {
        val budget = budget()
        var next = roots
        index.beginBatch()
        try {
        for ((identity, record) in records) {
            currentCoroutineContext().ensureActive()
            val identityKey = exact(identity.value)
            val oldKey = index.get(next.identity, identityKey, budget)
            var order = next.order
            if (oldKey != null) order = index.delete(order, oldKey, budget)
            if (record == null) {
                next = next.copy(order = order, identity = index.delete(next.identity, identityKey, budget))
            } else {
                val key = encodeKey(record.key)
                val ref = writeBlob(record.body, budget)
                val metadata = encode { writeLong(originalRevisions[identity] ?: checkpoint.revision); writeUTF(record.contentType)
                    writeLong(record.body.size.toLong()); writeUTF(ref) }
                next = next.copy(order = index.put(order, key, metadata, budget),
                    identity = index.put(next.identity, identityKey, key, budget))
            }
        }
        for ((key, bytes) in evidence) {
            currentCoroutineContext().ensureActive()
            val ref = bytes?.let { writeBlob(it, budget) }
            next = next.copy(evidence = if (bytes == null) index.delete(next.evidence, exact(key), budget)
                else index.put(next.evidence, exact(key), encode { writeLong(bytes.size.toLong()); writeUTF(ref) }, budget))
        }
        require(toolGeneration >= roots.toolGeneration)
        for ((callId, entry) in tools) {
            currentCoroutineContext().ensureActive()
            require(callId == entry.callId)
            val key = exact(callId)
            val value = encode {
                writeBoolean(entry.owner != null)
                entry.owner?.let { val bytes = exact(it.value); writeInt(bytes.size); write(bytes) }
                writeBoolean(entry.returned)
            }
            next = next.copy(tools = index.put(next.tools, key, value, budget),
                unresolved = if (entry.owner != null && !entry.returned)
                    index.put(next.unresolved, key, value, budget)
                else index.delete(next.unresolved, key, budget))
        }
        next = next.copy(toolGeneration = toolGeneration)
        val context = currentCoroutineContext()
        index.publish(next, codec.encode(checkpoint), budget) { context.ensureActive() }
        } finally { index.abandonBatch() }
    }

    private fun writeBlob(bytes: ByteArray, budget: DesktopPersistentTimelineIndex.Budget): String {
        require(bytes.size <= MAX_BYTES)
        val refs = (bytes.indices step CHUNK).map { start ->
            index.putObject(bytes.copyOfRange(start, minOf(start + CHUNK, bytes.size)), budget)
        }
        return index.putObject(encode { writeLong(bytes.size.toLong()); writeInt(refs.size); refs.forEach(::writeUTF) }, budget)
    }

    private fun readBlob(ref: String, size: Long, offset: Long, maxBytes: Int,
        budget: DesktopPersistentTimelineIndex.Budget): ByteArray {
        val data = DataInputStream(ByteArrayInputStream(index.objectBytes(ref, 12 + 66 * (MAX_BYTES / CHUNK), budget)))
        require(data.readLong() == size && size in 0..MAX_BYTES.toLong())
        val count = data.readInt()
        require(count == ((size + CHUNK - 1) / CHUNK).toInt())
        val refs = List(count) { data.readUTF() }
        require(data.available() == 0)
        val result = ByteArray(minOf(maxBytes.toLong(), size - offset).toInt())
        var copied = 0
        while (copied < result.size) {
            val position = offset + copied
            val chunk = (position / CHUNK).toInt()
            val bytes = index.objectBytes(refs[chunk], CHUNK, budget)
            require(bytes.size.toLong() == minOf(CHUNK.toLong(), size - chunk.toLong() * CHUNK))
            val within = (position % CHUNK).toInt()
            val length = minOf(bytes.size - within, result.size - copied)
            bytes.copyInto(result, copied, within, within + length)
            copied += length
        }
        return result
    }

    companion object {
        const val MAX_ROWS = 2048
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val CHUNK = 64 * 1024
        fun exact(value: String): ByteArray {
            require(value.length <= 256)
            return ByteBuffer.allocate(value.length * 2).also { buffer -> value.forEach(buffer::putChar) }.array()
        }
        private fun encodeKey(key: TimelinePageKey): ByteArray =
            ByteBuffer.allocate(8 + exact(key.identity.value).size).putLong(key.order xor Long.MIN_VALUE)
                .put(exact(key.identity.value)).array()
        private fun decodeKey(bytes: ByteArray): TimelinePageKey {
            require(bytes.size in 8..520 && bytes.size % 2 == 0)
            val buffer = ByteBuffer.wrap(bytes)
            val order = buffer.long xor Long.MIN_VALUE
            val identity = buildString { while (buffer.hasRemaining()) append(buffer.char) }
            return TimelinePageKey(order, TimelineMessageId(identity))
        }
        private fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
            DataOutputStream(it).use(block)
        }.toByteArray()
    }
}
