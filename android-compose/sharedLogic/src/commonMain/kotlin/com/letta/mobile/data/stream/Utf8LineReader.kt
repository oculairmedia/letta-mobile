package com.letta.mobile.data.stream

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

internal class Utf8LineReader(
    private val channel: ByteReadChannel,
    private val chunkSize: Int = 1024,
) {
    private val pending = StringBuilder()
    private val buffer = ByteArray(chunkSize)

    suspend fun readLine(): String? {
        while (true) {
            val newlineIndex = pending.indexOf("\n")
            if (newlineIndex >= 0) {
                val line = pending.substring(0, newlineIndex).removeSuffix("\r")
                pending.delete(0, newlineIndex + 1)
                return line
            }

            val read = channel.readAvailable(buffer, 0, buffer.size)
            when {
                read > 0 -> pending.append(buffer.decodeToString(endIndex = read))
                read == -1 -> {
                    if (pending.isEmpty()) return null
                    val line = pending.toString().removeSuffix("\r")
                    pending.clear()
                    return line
                }
            }
        }
    }
}
