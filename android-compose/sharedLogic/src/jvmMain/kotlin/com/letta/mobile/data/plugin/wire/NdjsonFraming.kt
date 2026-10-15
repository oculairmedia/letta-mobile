package com.letta.mobile.data.plugin.wire

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NDJSON framing (plan section 5, `process` runtime): one message per `\n`-terminated UTF-8 line
 * and no other bytes. A `\r` before the `\n` is tolerated and blank lines are skipped; a line over
 * [maxLineBytes] is dropped as it streams (never buffered whole) and reported as one
 * [LcpInboundFrame.Oversized].
 */
class NdjsonFramer(private val maxLineBytes: Int = LcpWire.MAX_MESSAGE_BYTES) {
    private val line = GrowableBytes()
    private var dropped: Long = -1

    /** The frames [chunk] completes, in order. */
    fun feed(chunk: ByteArray): List<LcpInboundFrame> {
        val frames = mutableListOf<LcpInboundFrame>()
        var start = 0
        while (start <= chunk.size) {
            val newline = chunk.indexOf(NEWLINE, start)
            val end = if (newline < 0) chunk.size else newline
            take(chunk, start, end)
            if (newline < 0) break
            completeLine()?.let(frames::add)
            start = newline + 1
        }
        return frames
    }

    /** The last frame when the stream ends without a final line break. */
    fun finish(): LcpInboundFrame? = completeLine()

    private fun take(chunk: ByteArray, from: Int, to: Int) {
        val count = to - from
        when {
            dropped >= 0 -> dropped += count
            line.size + count > maxLineBytes -> {
                dropped = (line.size + count).toLong()
                line.clear()
            }
            else -> line.append(chunk, from, to)
        }
    }

    private fun completeLine(): LcpInboundFrame? {
        if (dropped >= 0) return LcpInboundFrame.Oversized(dropped).also { dropped = -1 }
        val text = line.decode().removeSuffix("\r")
        line.clear()
        return text.takeIf { it.isNotBlank() }?.let(LcpInboundFrame::Text)
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A

        fun ByteArray.indexOf(byte: Byte, from: Int): Int {
            for (index in from until size) if (this[index] == byte) return index
            return -1
        }
    }
}

/** A byte buffer that grows by doubling; commonMain has no ByteArrayOutputStream. */
private class GrowableBytes {
    private var bytes = ByteArray(INITIAL)
    var size: Int = 0
        private set

    fun append(source: ByteArray, from: Int, to: Int) {
        val needed = size + (to - from)
        if (needed > bytes.size) bytes = bytes.copyOf(maxOf(needed, bytes.size * 2))
        source.copyInto(bytes, size, from, to)
        size = needed
    }

    fun decode(): String = bytes.decodeToString(0, size)

    fun clear() {
        size = 0
        if (bytes.size > INITIAL) bytes = ByteArray(INITIAL)
    }

    private companion object {
        const val INITIAL = 4096
    }
}

/** Where an NDJSON transport reads: the next chunk of bytes, or null at the end of the stream. */
fun interface LcpByteSource {
    suspend fun read(): ByteArray?
}

/** Where an NDJSON transport writes. */
interface LcpByteSink {
    suspend fun write(bytes: ByteArray)

    fun close()
}

/**
 * A [LcpTransport] over a byte stream pair (a subprocess's stdout and stdin in the `process`
 * driver): frames with [NdjsonFramer], writes each message as one line under a lock so concurrent
 * senders never interleave.
 */
class NdjsonLcpTransport(
    private val source: LcpByteSource,
    private val sink: LcpByteSink,
    maxLineBytes: Int = LcpWire.MAX_MESSAGE_BYTES,
) : LcpTransport {
    private val framer = NdjsonFramer(maxLineBytes)
    private val ready = ArrayDeque<LcpInboundFrame>()
    private val writing = Mutex()
    private var ended = false

    override suspend fun send(text: String) {
        require('\n' !in text) { "an NDJSON message is one line" }
        writing.withLock { sink.write((text + "\n").encodeToByteArray()) }
    }

    override suspend fun receive(): LcpInboundFrame? {
        while (ready.isEmpty() && !ended) fill()
        return ready.removeFirstOrNull()
    }

    private suspend fun fill() {
        val chunk = source.read()
        if (chunk == null) {
            ended = true
            framer.finish()?.let(ready::add)
        } else {
            ready.addAll(framer.feed(chunk))
        }
    }

    override fun close() {
        sink.close()
    }
}

/** What a WebSocket session offers a [TextFrameLcpTransport]: whole text frames. */
interface LcpTextFrames {
    suspend fun send(text: String)

    /** The next text frame, or null once the socket is closed. */
    suspend fun receive(): String?

    fun close()
}

/**
 * The WebSocket framing (plan section 5, `service` runtime): one message per text frame, the same
 * codec and the same size cap as NDJSON. A frame over the cap is reported as oversized unread.
 */
class TextFrameLcpTransport(private val frames: LcpTextFrames) : LcpTransport {
    override suspend fun send(text: String) = frames.send(text)

    override suspend fun receive(): LcpInboundFrame? {
        val text = frames.receive() ?: return null
        val size = JsonRpcCodec.utf8Size(text)
        return if (size > LcpWire.MAX_MESSAGE_BYTES) LcpInboundFrame.Oversized(size.toLong()) else LcpInboundFrame.Text(text)
    }

    override fun close() = frames.close()
}
