package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.wire.LcpByteSink
import com.letta.mobile.data.plugin.wire.LcpByteSource
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpErrorCode
import com.letta.mobile.data.plugin.wire.LcpInboundFrame
import com.letta.mobile.data.plugin.wire.LcpTransport
import com.letta.mobile.data.plugin.wire.LcpWire
import com.letta.mobile.data.plugin.wire.NdjsonFramer
import com.letta.mobile.data.plugin.wire.NdjsonLcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * LCP over a `process` plugin's stdio (wire v1 section "Framing"): NDJSON on stdin (host to plugin)
 * and stdout (plugin to host), each message at most [maxMessageBytes] (4 MiB: a longer stdout line
 * is dropped as it streams and reported oversized; the peer refuses longer outgoing messages).
 * stderr is never protocol: each line goes to [onStderr] for telemetry, unscrubbed (the caller
 * scrubs), cut at [STDERR_LINE_BYTES].
 *
 * [close] closes stdin, which a well-behaved plugin reads as "exit now". Blocking reads of stdout
 * and stderr end when the process does, so the owner kills the process after closing; the stderr
 * pump runs in [scope] and ends with the process ([stderrPump]).
 */
class StdioLcpTransport(
    process: Process,
    scope: CoroutineScope,
    onStderr: (String) -> Unit,
    maxMessageBytes: Int = LcpWire.MAX_MESSAGE_BYTES,
) : LcpTransport {
    private val ndjson = NdjsonLcpTransport(StreamSource(process.inputStream), StreamSink(process.outputStream), maxMessageBytes)

    /** Copies stderr lines to the callback until the process closes stderr. */
    val stderrPump: Job = scope.launch(Dispatchers.IO) { pumpLines(process.errorStream, onStderr) }

    override suspend fun send(text: String) = ndjson.send(text)

    override suspend fun receive(): LcpInboundFrame? = ndjson.receive()

    override fun close() = ndjson.close()

    companion object {
        /** The longest stderr line kept; a longer one is reported by its size only. */
        const val STDERR_LINE_BYTES: Int = 8 * 1024

        private const val READ_BUFFER: Int = 64 * 1024

        private suspend fun pumpLines(stream: InputStream, onLine: (String) -> Unit) {
            val framer = NdjsonFramer(STDERR_LINE_BYTES)
            val source = StreamSource(stream)
            while (true) {
                val chunk = source.read() ?: break
                framer.feed(chunk).forEach { onLine(it.describe()) }
            }
            framer.finish()?.let { onLine(it.describe()) }
        }

        private fun LcpInboundFrame.describe(): String = when (this) {
            is LcpInboundFrame.Text -> text
            is LcpInboundFrame.Oversized -> "[stderr line of $bytes bytes dropped]"
        }
    }

    private class StreamSource(private val stream: InputStream) : LcpByteSource {
        private val buffer = ByteArray(READ_BUFFER)

        override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
            val count = try {
                stream.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (count < 0) null else buffer.copyOf(count)
        }
    }

    private class StreamSink(private val stream: OutputStream) : LcpByteSink {
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            try {
                stream.write(bytes)
                stream.flush()
            } catch (e: IOException) {
                throw LcpCallException(LcpErrorCode.CLOSED, "the plugin's stdin is closed: ${e.message.orEmpty()}")
            }
        }

        override fun close() {
            try {
                stream.close()
            } catch (_: IOException) {
                // Already gone with the process; nothing left to flush.
            }
        }
    }
}
