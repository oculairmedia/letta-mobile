package com.letta.mobile.cli.meridian

import com.letta.mobile.data.meridian.endpoint.MeridianToolsService
import com.letta.mobile.data.meridian.endpoint.MeridianToolsWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Moves `meridian/tools/1` lines between a listening socket and [MeridianToolsService]
 * (letta-mobile-jna0o.4): accept, read one request line (capped at
 * [MeridianToolsWire.MAX_REQUEST_LINE_BYTES], within [readTimeoutMs]), answer one response line,
 * close. At most [maxConcurrent] calls run at once; more wait for a slot.
 */
class MeridianToolsSocketServer(
    private val service: MeridianToolsService,
    private val log: (String) -> Unit,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    private val slots = Semaphore(maxConcurrent)

    /** Serves [server] until cancelled, then closes it. */
    suspend fun serve(server: ServerSocketChannel) {
        try {
            coroutineScope {
                while (true) {
                    slots.acquire()
                    val connection = try {
                        runInterruptible(Dispatchers.IO) { server.accept() }
                    } catch (e: Throwable) {
                        slots.release()
                        throw e
                    }
                    launch(Dispatchers.IO) {
                        try {
                            answer(connection)
                        } finally {
                            slots.release()
                        }
                    }
                }
            }
        } finally {
            runCatching { server.close() }
        }
    }

    private suspend fun answer(connection: SocketChannel) {
        connection.use { channel ->
            val reply = try {
                val line = withTimeout(readTimeoutMs) { runInterruptible { readLine(Channels.newInputStream(channel)) } }
                if (line == null) MeridianToolsService.tooLarge() else service.handle(line)
            } catch (e: TimeoutCancellationException) {
                log("[meridian-tools] request not received within ${readTimeoutMs}ms; closed")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log("[meridian-tools] connection failed: ${e.message}")
                return
            }
            withContext(Dispatchers.IO) { writeLine(channel, reply) }
        }
    }

    private fun writeLine(channel: SocketChannel, line: String) {
        val buffer = ByteBuffer.wrap((line + "\n").encodeToByteArray())
        try {
            while (buffer.hasRemaining()) channel.write(buffer)
        } catch (e: IOException) {
            log("[meridian-tools] client went away before the answer: ${e.message}")
        }
    }

    companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_CONCURRENT = 16
        private val SOCKET_MODE = PosixFilePermissions.fromString("rw-rw----")

        /**
         * One line from [input] without its `\n` (or up to EOF), or null when it is longer than
         * [maxBytes]. An empty stream reads as an empty line.
         */
        internal fun readLine(input: InputStream, maxBytes: Int = MeridianToolsWire.MAX_REQUEST_LINE_BYTES): String? {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                val newline = (0 until read).firstOrNull { chunk[it] == NEWLINE }
                val take = newline ?: read
                if (out.size() + take > maxBytes) return null
                out.write(chunk, 0, take)
                if (newline != null) break
            }
            return out.toString(Charsets.UTF_8)
        }

        /**
         * Binds the Unix socket at [path], replacing a stale socket file (never a regular file),
         * with mode 0660 where the filesystem has POSIX permissions.
         */
        fun bindUnix(path: Path): ServerSocketChannel {
            if (Files.isRegularFile(path) || Files.isDirectory(path)) {
                throw IOException("$path exists and is not a socket; refusing to replace it")
            }
            Files.deleteIfExists(path)
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                server.bind(UnixDomainSocketAddress.of(path))
                runCatching { Files.setPosixFilePermissions(path, SOCKET_MODE) }
                    .onFailure { if (it !is UnsupportedOperationException) throw it }
            } catch (e: IOException) {
                server.close()
                throw e
            }
            return server
        }

        /** Binds loopback TCP on [port] (0 = OS-assigned); never a routable address. */
        fun bindLoopback(port: Int): ServerSocketChannel =
            ServerSocketChannel.open().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }

        private const val CHUNK = 64 * 1024
        private const val NEWLINE = '\n'.code.toByte()
    }
}
