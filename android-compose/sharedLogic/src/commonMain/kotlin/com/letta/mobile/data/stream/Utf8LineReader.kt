package com.letta.mobile.data.stream

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

internal class Utf8LineReader(
    private val channel: ByteReadChannel,
    chunkSize: Int = 1024,
) {
    private val pending = StringBuilder()
    private val buffer = ByteArray(chunkSize)

    // letta-mobile-h30cy: readAvailable can return a chunk that ends in the
    // MIDDLE of a multi-byte UTF-8 sequence (e.g. em dash "—" = E2 80 94 split
    // across two reads). decodeToString on the truncated bytes drops the
    // codepoint / emits U+FFFD, and the trailing partial bytes were lost — which
    // showed up as character drops on the streaming path (and, on transports that
    // retry on decode error, as duplicated messages). Fix: carry the incomplete
    // trailing bytes forward and only decode up to the last COMPLETE codepoint
    // boundary, prepending the carry to the next read.
    private var carry = ByteArray(0)

    suspend fun readLine(): String? {
        while (true) {
            val newlineIndex = pending.indexOf("\n")
            if (newlineIndex >= 0) {
                val line = pending.substring(0, newlineIndex).removeSuffix("\r")
                val remainder = pending.substring(newlineIndex + 1)
                pending.setLength(0)
                pending.append(remainder)
                return line
            }

            val read = channel.readAvailable(buffer, 0, buffer.size)
            when {
                read > 0 -> {
                    // Combine any carried-over partial bytes with the new read.
                    val combined = if (carry.isEmpty()) {
                        buffer.copyOfRange(0, read)
                    } else {
                        carry + buffer.copyOfRange(0, read)
                    }
                    // Decode only up to the last complete UTF-8 codepoint; keep
                    // the trailing incomplete bytes (if any) for the next read.
                    val completeLen = completeUtf8Length(combined)
                    if (completeLen > 0) {
                        pending.append(combined.decodeToString(0, completeLen))
                    }
                    carry = if (completeLen < combined.size) {
                        combined.copyOfRange(completeLen, combined.size)
                    } else {
                        EMPTY
                    }
                }
                read == -1 -> {
                    // End of stream: decode whatever remains (including any
                    // trailing partial bytes — decodeToString will substitute
                    // U+FFFD for a genuinely truncated tail, which is correct at EOF).
                    if (carry.isNotEmpty()) {
                        pending.append(carry.decodeToString())
                        carry = EMPTY
                    }
                    if (pending.isEmpty()) return null
                    val line = pending.toString().removeSuffix("\r")
                    pending.clear()
                    return line
                }
            }
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /**
         * Returns the number of leading bytes in [bytes] that form COMPLETE UTF-8
         * codepoints. Any trailing bytes that begin a multi-byte sequence which
         * is not yet fully present are excluded, so the caller can carry them to
         * the next read instead of decoding a truncated codepoint.
         */
        internal fun completeUtf8Length(bytes: ByteArray): Int {
            val size = bytes.size
            if (size == 0) return 0
            // A UTF-8 codepoint is at most 4 bytes, so we only need to inspect the
            // last few bytes. Walk back to the start byte of the final sequence.
            var i = size - 1
            // Skip continuation bytes (10xxxxxx).
            while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80) {
                i--
            }
            if (i < 0) {
                // All continuation bytes with no lead — malformed; treat as complete
                // so we don't stall (decodeToString will substitute U+FFFD).
                return size
            }
            val lead = bytes[i].toInt() and 0xFF
            val expected = when {
                lead and 0x80 == 0x00 -> 1 // 0xxxxxxx
                lead and 0xE0 == 0xC0 -> 2 // 110xxxxx
                lead and 0xF0 == 0xE0 -> 3 // 1110xxxx
                lead and 0xF8 == 0xF0 -> 4 // 11110xxx
                else -> 1 // invalid lead byte — let decodeToString handle it
            }
            val available = size - i
            return if (available >= expected) {
                // Final sequence is complete — everything decodes.
                size
            } else {
                // Final sequence is truncated — decode up to its start byte.
                i
            }
        }
    }
}
