package com.letta.mobile.desktop.data

import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.UUID

/** Internal disk format only. Callers own serialization, evidence semantics and IO dispatch.
 * Immutable generations keep existing readers valid. Garbage collection is deliberately separate.
 * A directory belongs to one scope. Writers are fenced with an OS lock, including across processes.
 */
internal class DesktopIndexedTimelineFiles(private val directory: Path) {
    internal data class Key(val order: Long, val identity: String) : Comparable<Key> {
        init { require(identity.length in 1..KEY_CHARS) }
        override fun compareTo(other: Key): Int =
            order.compareTo(other.order).takeIf { it != 0 } ?: identity.compareTo(other.identity)
    }

    internal data class Entry(
        val key: Key,
        val metadata: ByteArray,
        val bodyBytes: Long,
        val body: () -> InputStream,
    )

    internal data class Metadata(
        val key: Key,
        val bytes: ByteArray,
        val bodyOffset: Long,
        val bodyBytes: Long,
        val generation: String,
        val ordinal: Long,
    )

    internal enum class Direction { BEFORE, AFTER }

    /** Checkpoint may throw CancellationException; the final checkpoint precedes atomic publication.
     * Once publication linearizes there are no cancellation callbacks. A thrown IO error at directory
     * fsync may mean publication succeeded; callers must reopen rather than assume rollback.
     */
    fun publish(entries: Sequence<Entry>, checkpoint: () -> Unit = {}): Snapshot {
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve("writer.lock"), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.tryLock().use { lock ->
                check(lock != null) { "Another timeline writer is active" }
                checkpoint()
                val previous = open()?.generation
                val generation = UUID.randomUUID().toString()
                val indexPath = directory.resolve("$generation.index")
                val bodyPath = directory.resolve("$generation.body")
                RandomAccessFile(indexPath.toFile(), "rw").use { index ->
                    RandomAccessFile(bodyPath.toFile(), "rw").use { bodies ->
                        index.writeLong(MAGIC)
                        index.writeLong(0)
                        var count = 0L
                        var last: Key? = null
                        for (entry in entries) {
                            checkpoint()
                            require(last == null || last < entry.key) { "Entries must be strictly sorted" }
                            require(entry.metadata.size <= META_BYTES && entry.bodyBytes >= 0)
                            val offset = bodies.filePointer
                            writeBody(bodies, entry, checkpoint)
                            val record = ByteBuffer.allocate(RECORD_BYTES - HASH_BYTES)
                            record.putLong(entry.key.order)
                            record.putInt(entry.key.identity.length)
                            repeat(KEY_CHARS) { record.putChar(entry.key.identity.getOrNull(it) ?: '\u0000') }
                            record.putInt(entry.metadata.size)
                            record.put(entry.metadata)
                            record.position(8 + 4 + KEY_CHARS * 2 + 4 + META_BYTES)
                            record.putLong(offset).putLong(entry.bodyBytes)
                            index.write(record.array())
                            index.write(hash(record.array()))
                            count++
                            last = entry.key
                        }
                        index.seek(8)
                        index.writeLong(count)
                        bodies.fd.sync()
                        index.fd.sync()
                    }
                }
                syncDirectory()
                checkpoint()
                val manifest = directory.resolve("$generation.pending")
                RandomAccessFile(manifest.toFile(), "rw").use {
                    it.writeUTF(generation)
                    it.writeUTF(previous ?: "")
                    it.fd.sync()
                }
                checkpoint()
                // No non-atomic replacement fallback: unsupported filesystems fail closed.
                Files.move(manifest, directory.resolve("active"), ATOMIC_MOVE, REPLACE_EXISTING)
                syncDirectory()
                return snapshot(generation)
            }
        }
    }

    fun open(): Snapshot? {
        val manifest = directory.resolve("active")
        if (!Files.exists(manifest)) return null
        val names = RandomAccessFile(manifest.toFile(), "r").use {
            require(it.length() <= 80) { "Invalid manifest size" }
            listOf(it.readUTF(), it.readUTF())
        }
        var failure: Exception? = null
        for (name in names.filter { it.isNotEmpty() }) {
            try { return snapshot(name) } catch (error: Exception) { failure = error }
        }
        throw requireNotNull(failure)
    }

    private fun snapshot(generation: String): Snapshot {
        require(UUID.fromString(generation).toString() == generation)
        val count = RandomAccessFile(directory.resolve("$generation.index").toFile(), "r").use {
            require(it.readLong() == MAGIC) { "Unknown index format" }
            val rows = it.readLong()
            require(rows >= 0 && rows <= (Long.MAX_VALUE - HEADER_BYTES) / RECORD_BYTES)
            require(it.length() == HEADER_BYTES + rows * RECORD_BYTES) { "Incomplete index" }
            rows
        }
        val bodyPath = directory.resolve("$generation.body")
        require(Files.isRegularFile(bodyPath))
        val snapshot = Snapshot(generation, count)
        // Validate the terminal extent without scanning history or decoding bodies at startup.
        val last = snapshot.tail(1).singleOrNull()
        val expectedBytes = if (last == null) 0L else {
            val chunks = last.bodyBytes / CHUNK_BYTES + if (last.bodyBytes % CHUNK_BYTES == 0L) 0 else 1
            val overhead = Math.multiplyExact(chunks, (HASH_BYTES + 4).toLong())
            Math.addExact(last.bodyOffset, Math.addExact(last.bodyBytes, overhead))
        }
        require(Files.size(bodyPath) == expectedBytes) { "Incomplete body generation" }
        return snapshot
    }

    internal inner class Snapshot internal constructor(val generation: String, val count: Long) {
        /** Actual index record bytes read, including binary-search probes. No body reads. */
        var indexBytesRead: Long = 0
            private set

        /** Tail has no sentinel key: Long.MAX_VALUE and every valid identity remain addressable. */
        fun tail(limit: Int): List<Metadata> {
            require(limit in 1..MAX_PAGE_ROWS)
            RandomAccessFile(directory.resolve("$generation.index").toFile(), "r").use { index ->
                return ((count - limit).coerceAtLeast(0) until count).map { readMetadata(index, it) }
            }
        }

        fun seek(key: Key, limit: Int, direction: Direction): List<Metadata> {
            require(limit in 1..MAX_PAGE_ROWS)
            RandomAccessFile(directory.resolve("$generation.index").toFile(), "r").use { index ->
                var low = 0L
                var high = count
                while (low < high) {
                    val mid = low + (high - low) / 2
                    val candidate = readMetadata(index, mid).key
                    if (candidate < key || (direction == Direction.AFTER && candidate == key)) low = mid + 1
                    else high = mid
                }
                val start = if (direction == Direction.AFTER) low else (low - limit).coerceAtLeast(0)
                val end = if (direction == Direction.AFTER) (low + limit).coerceAtMost(count) else low
                return (start until end).map { readMetadata(index, it) }
            }
        }

        /** Reads at most maxBytes plus two boundary chunks; every accessed chunk is verified first.
         * Returned bytes are exact, without UTF-8 truncation/re-encoding. Metadata must come from this snapshot.
         */
        fun readBody(row: Metadata, offset: Long, maxBytes: Int, checkpoint: () -> Unit = {}): ByteArray {
            require(row.generation == generation) { "Body pointer belongs to another generation" }
            require(row.ordinal in 0 until count) { "Invalid body pointer ordinal" }
            RandomAccessFile(directory.resolve("$generation.index").toFile(), "r").use { index ->
                val stored = readMetadata(index, row.ordinal)
                require(stored.key == row.key && stored.bodyOffset == row.bodyOffset &&
                    stored.bodyBytes == row.bodyBytes && stored.bytes.contentEquals(row.bytes)) {
                    "Body pointer does not match indexed row"
                }
            }
            require(offset in 0..row.bodyBytes && maxBytes in 1..MAX_BODY_READ)
            val size = minOf(maxBytes.toLong(), row.bodyBytes - offset).toInt()
            val result = ByteArray(size)
            RandomAccessFile(directory.resolve("$generation.body").toFile(), "r").use { body ->
                var copied = 0
                while (copied < size) {
                    checkpoint()
                    val position = offset + copied
                    val chunk = position / CHUNK_BYTES
                    val within = (position % CHUNK_BYTES).toInt()
                    body.seek(Math.addExact(row.bodyOffset, Math.multiplyExact(chunk, (CHUNK_BYTES + HASH_BYTES + 4).toLong())))
                    val length = body.readInt()
                    val expectedLength = minOf(CHUNK_BYTES.toLong(), row.bodyBytes - chunk * CHUNK_BYTES).toInt()
                    require(length == expectedLength && length > within) { "Invalid body chunk" }
                    val checksum = ByteArray(HASH_BYTES).also(body::readFully)
                    val bytes = ByteArray(length).also(body::readFully)
                    require(MessageDigest.isEqual(checksum, hash(bytes))) { "Body checksum mismatch" }
                    val take = minOf(length - within, size - copied)
                    bytes.copyInto(result, copied, within, within + take)
                    copied += take
                }
            }
            return result
        }

        private fun readMetadata(index: RandomAccessFile, ordinal: Long): Metadata {
            index.seek(HEADER_BYTES + ordinal * RECORD_BYTES)
            val bytes = ByteArray(RECORD_BYTES - HASH_BYTES).also(index::readFully)
            val checksum = ByteArray(HASH_BYTES).also(index::readFully)
            indexBytesRead += RECORD_BYTES
            require(MessageDigest.isEqual(checksum, hash(bytes))) { "Index checksum mismatch" }
            val record = ByteBuffer.wrap(bytes)
            val order = record.long
            val length = record.int
            require(length in 1..KEY_CHARS)
            val identity = CharArray(KEY_CHARS) { record.char }.concatToString().substring(0, length)
            val metadataSize = record.int
            require(metadataSize in 0..META_BYTES)
            val metadata = ByteArray(metadataSize).also(record::get)
            record.position(8 + 4 + KEY_CHARS * 2 + 4 + META_BYTES)
            val offset = record.long
            val size = record.long
            require(offset >= 0 && size >= 0)
            return Metadata(Key(order, identity), metadata, offset, size, generation, ordinal)
        }
    }

    private fun writeBody(file: RandomAccessFile, entry: Entry, checkpoint: () -> Unit) {
        entry.body().use { input ->
            var remaining = entry.bodyBytes
            while (remaining > 0) {
                checkpoint()
                val bytes = ByteArray(minOf(CHUNK_BYTES.toLong(), remaining).toInt())
                var read = 0
                while (read < bytes.size) {
                    checkpoint()
                    val amount = input.read(bytes, read, bytes.size - read)
                    require(amount > 0) { "Body ended before declared length (or stream made no progress)" }
                    read += amount
                }
                file.writeInt(bytes.size)
                file.write(hash(bytes))
                file.write(bytes)
                remaining -= bytes.size
            }
            require(input.read() == -1) { "Body exceeds declared length" }
        }
    }

    private fun syncDirectory() = FileChannel.open(directory, READ).use { it.force(true) }

    private companion object {
        const val MAGIC = 0x4c54495800000001L
        const val HEADER_BYTES = 16L
        const val KEY_CHARS = 256
        const val META_BYTES = 1024
        const val HASH_BYTES = 32
        const val CHUNK_BYTES = 64 * 1024
        const val MAX_BODY_READ = 1024 * 1024
        const val MAX_PAGE_ROWS = 256
        const val RECORD_BYTES = 8 + 4 + KEY_CHARS * 2 + 4 + META_BYTES + 8 + 8 + HASH_BYTES
        fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
