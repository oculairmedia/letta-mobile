package com.letta.mobile.data.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * The host's durable [CanvasRelayStore]: under [root], one directory per topic (named by its
 * SHA-256) holding `binding` (the canvas id, written once, atomically) and `ops.jsonl` (one entry
 * per line: cursor, origin, op). Every append is forced to disk before it returns, because the relay
 * acknowledges an op as soon as this does - an ack must survive a crash.
 *
 * A torn last line (a crash mid-write) is ignored on load; the op it held was never acknowledged, so
 * its app still has it queued and sends it again.
 */
class FileCanvasRelayStore(private val root: Path) : CanvasRelayStore {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val loaded = mutableMapOf<String, TopicIndex>()

    /** A topic's log as loaded: every entry, and each op id's cursor. */
    private class TopicIndex(val entries: MutableList<CanvasRelayEntry>, val cursorsByOp: MutableMap<String, Long>)

    @Serializable
    private data class Line(val cursor: Long, val origin: String, val op: CanvasOp)

    override suspend fun bind(topic: String, proposed: CanvasId): CanvasId = io {
        val file = dirOf(topic).resolve(BINDING)
        if (Files.exists(file)) return@io CanvasId(Files.readString(file).trim())
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "binding", ".tmp")
        Files.writeString(temp, proposed.value)
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
        proposed
    }

    override suspend fun append(topic: String, op: CanvasOp, origin: String): CanvasRelayAppend = io {
        val index = indexOf(topic)
        index.cursorsByOp[op.opId]?.let { return@io CanvasRelayAppend(it, duplicate = true) }
        val cursor = index.entries.size + 1L
        val line = json.encodeToString(Line.serializer(), Line(cursor, origin, op)) + "\n"
        val file = dirOf(topic).resolve(OPS)
        Files.createDirectories(file.parent)
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        index.entries += CanvasRelayEntry(cursor, op, origin)
        index.cursorsByOp[op.opId] = cursor
        CanvasRelayAppend(cursor, duplicate = false)
    }

    override suspend fun readAfter(topic: String, afterCursor: Long, limit: Int): List<CanvasRelayEntry> = io {
        val entries = indexOf(topic).entries
        val from = afterCursor.coerceIn(0L, entries.size.toLong()).toInt()
        entries.subList(from, minOf(entries.size, from + limit)).toList()
    }

    override suspend fun head(topic: String): Long = io { indexOf(topic).entries.size.toLong() }

    private fun indexOf(topic: String): TopicIndex = loaded.getOrPut(topic) {
        val file = dirOf(topic).resolve(OPS)
        val entries = mutableListOf<CanvasRelayEntry>()
        if (Files.exists(file)) {
            dropTornTail(file)
            for (raw in Files.readAllLines(file)) {
                val line = runCatching { json.decodeFromString(Line.serializer(), raw) }.getOrNull() ?: continue
                // Cursors are positions; a line out of sequence is damage, and so is all after it.
                if (line.cursor != entries.size + 1L) break
                entries += CanvasRelayEntry(line.cursor, line.op, line.origin)
            }
        }
        TopicIndex(entries, entries.associateTo(mutableMapOf()) { it.op.opId to it.cursor })
    }

    /**
     * Cuts a partial last line (a crash mid-append) back to the last newline. Left in place, the
     * next append would continue it and be lost with it.
     */
    private fun dropTornTail(file: Path) {
        FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            var end = channel.size()
            val one = ByteBuffer.allocate(1)
            while (end > 0) {
                one.clear()
                channel.read(one, end - 1)
                if (one.get(0) == '\n'.code.toByte()) break
                end--
            }
            if (end < channel.size()) channel.truncate(end)
        }
    }

    private fun dirOf(topic: String): Path = root.resolve(sha256(topic))

    private suspend fun <T> io(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val BINDING = "binding"
        const val OPS = "ops.jsonl"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
