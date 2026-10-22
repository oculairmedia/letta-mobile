package com.letta.mobile.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** What [readUtf8AtMost] kept of a stream, and whether the stream went on past it. */
internal data class BoundedText(val text: String, val truncated: Boolean)

/**
 * Reads this stream as UTF-8, keeping only the first [maxBytes] bytes and stopping as soon as the
 * stream is known to go past them. A multi-byte character cut at the cap decodes as U+FFFD.
 */
internal fun InputStream.readUtf8AtMost(maxBytes: Int): BoundedText {
    val kept = ByteArrayOutputStream(minOf(maxBytes, CHUNK_BYTES))
    val chunk = ByteArray(CHUNK_BYTES)
    var truncated = false
    while (!truncated) {
        val read = read(chunk)
        if (read < 0) break
        val room = maxBytes - kept.size()
        if (read > room) truncated = true
        kept.write(chunk, 0, minOf(read, room))
    }
    return BoundedText(kept.toString(Charsets.UTF_8.name()), truncated)
}

private const val CHUNK_BYTES = 8 * 1024
