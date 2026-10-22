package com.letta.mobile.data.controller.extras

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Empties one pipe of a CLI child on its own daemon thread from the moment it starts, keeping the
 * first [maxBytes] and discarding the rest, so a child that writes more than a pipe buffer holds
 * still exits. The thread is not from a shared pool: when a grandchild keeps the pipe open past the
 * CLI's exit, only this thread waits on it.
 */
internal class CliPipeDrain(stream: InputStream, private val maxBytes: Int, name: String) {
    private val kept = ByteArrayOutputStream()
    private val thread = Thread({ pump(stream) }, name).apply {
        isDaemon = true
        start()
    }

    /** What was read so far, after waiting up to [graceMs] for the pipe to close. */
    fun textAfter(graceMs: Long): String {
        thread.join(graceMs)
        return synchronized(kept) { kept.toString(Charsets.UTF_8.name()) }
    }

    private fun pump(stream: InputStream) {
        val chunk = ByteArray(CHUNK_BYTES)
        stream.use {
            while (true) {
                val read = runCatching { it.read(chunk) }.getOrDefault(-1)
                if (read < 0) break
                synchronized(kept) {
                    val room = maxBytes - kept.size()
                    if (room > 0) kept.write(chunk, 0, minOf(read, room))
                }
            }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 8 * 1024
    }
}
