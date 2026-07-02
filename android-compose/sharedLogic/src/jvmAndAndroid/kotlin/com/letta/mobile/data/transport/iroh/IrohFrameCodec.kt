package com.letta.mobile.data.transport.iroh

import computer.iroh.RecvStream
import computer.iroh.SendStream

/**
 * Length-delimited framing for App Server JSON payloads over Iroh streams.
 *
 * Wire format:
 * - 4-byte unsigned length prefix, big-endian network byte order
 * - exactly [length] UTF-8 JSON payload bytes
 *
 * The App Server JSON protocol is unchanged; only the stream framing changes.
 */
object IrohFrameCodec {
    const val DEFAULT_MAX_FRAME_BYTES: Int = 1_048_576 // 1 MiB
    private const val LENGTH_PREFIX_BYTES = 4

    class ProtocolException(message: String) : IllegalArgumentException(message)

    fun encodeFrame(frame: String, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES): ByteArray {
        val payload = frame.encodeToByteArray()
        require(payload.size <= maxFrameBytes) {
            "Iroh frame too large: ${payload.size} bytes > max $maxFrameBytes"
        }
        return encodeFrame(payload, maxFrameBytes)
    }

    fun encodeFrame(payload: ByteArray, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES): ByteArray {
        require(payload.size <= maxFrameBytes) {
            "Iroh frame too large: ${payload.size} bytes > max $maxFrameBytes"
        }
        val out = ByteArray(LENGTH_PREFIX_BYTES + payload.size)
        out[0] = ((payload.size ushr 24) and 0xff).toByte()
        out[1] = ((payload.size ushr 16) and 0xff).toByte()
        out[2] = ((payload.size ushr 8) and 0xff).toByte()
        out[3] = (payload.size and 0xff).toByte()
        payload.copyInto(out, destinationOffset = LENGTH_PREFIX_BYTES)
        return out
    }

    class Decoder(private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES) {
        private var expectedLength: Int? = null
        private val prefix = ByteArray(LENGTH_PREFIX_BYTES)
        private var prefixBytes = 0
        private var payload = ByteArray(0)
        private var payloadBytes = 0

        fun feed(chunk: ByteArray): List<String> {
            if (chunk.isEmpty()) return emptyList()
            val frames = mutableListOf<String>()
            var offset = 0
            while (offset < chunk.size) {
                val length = expectedLength
                if (length == null) {
                    val toCopy = minOf(LENGTH_PREFIX_BYTES - prefixBytes, chunk.size - offset)
                    chunk.copyInto(prefix, destinationOffset = prefixBytes, startIndex = offset, endIndex = offset + toCopy)
                    prefixBytes += toCopy
                    offset += toCopy
                    if (prefixBytes == LENGTH_PREFIX_BYTES) {
                        val decodedLength = decodeLength(prefix)
                        if (decodedLength > maxFrameBytes) {
                            throw ProtocolException("Iroh frame too large: $decodedLength bytes > max $maxFrameBytes")
                        }
                        expectedLength = decodedLength
                        payload = ByteArray(decodedLength)
                        payloadBytes = 0
                        prefixBytes = 0
                        if (decodedLength == 0) {
                            frames += ""
                            resetPayload()
                        }
                    }
                } else {
                    val toCopy = minOf(length - payloadBytes, chunk.size - offset)
                    chunk.copyInto(payload, destinationOffset = payloadBytes, startIndex = offset, endIndex = offset + toCopy)
                    payloadBytes += toCopy
                    offset += toCopy
                    if (payloadBytes == length) {
                        frames += payload.decodeToString()
                        resetPayload()
                    }
                }
            }
            return frames
        }

        fun finish() {
            if (prefixBytes != 0 || expectedLength != null) {
                throw ProtocolException("Truncated Iroh frame: stream ended mid-frame")
            }
        }

        private fun resetPayload() {
            expectedLength = null
            payload = ByteArray(0)
            payloadBytes = 0
        }
    }

    suspend fun write(sendStream: SendStream, frame: String, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES) {
        sendStream.writeAll(encodeFrame(frame, maxFrameBytes))
    }

    suspend fun readAll(
        recvStream: RecvStream,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
        chunkBytes: Int = 8192,
        onFrame: suspend (String) -> Unit,
    ) {
        val decoder = Decoder(maxFrameBytes)
        while (true) {
            val chunk = recvStream.read(chunkBytes.toUInt())
            if (chunk.isEmpty()) break
            decoder.feed(chunk).forEach { onFrame(it) }
        }
        decoder.finish()
    }

    private fun decodeLength(prefix: ByteArray): Int =
        ((prefix[0].toInt() and 0xff) shl 24) or
            ((prefix[1].toInt() and 0xff) shl 16) or
            ((prefix[2].toInt() and 0xff) shl 8) or
            (prefix[3].toInt() and 0xff)
}
