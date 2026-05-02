package com.letta.mobile.platform.storage

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readCapped(maxBytes: Int): Pair<ByteArray, Boolean> {
    val output = ByteArrayOutputStream(maxBytes.coerceAtMost(DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE.coerceAtMost(maxBytes + 1))

    while (output.size() <= maxBytes) {
        val remaining = maxBytes + 1 - output.size()
        val read = read(buffer, 0, buffer.size.coerceAtMost(remaining))
        if (read == -1) break
        output.write(buffer, 0, read)
    }

    val bytes = output.toByteArray()
    val truncated = bytes.size > maxBytes
    return bytes.copyOf(bytes.size.coerceAtMost(maxBytes)) to truncated
}

private const val DEFAULT_BUFFER_SIZE = 8 * 1024
