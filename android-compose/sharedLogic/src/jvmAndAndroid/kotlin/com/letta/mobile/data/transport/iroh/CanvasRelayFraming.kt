package com.letta.mobile.data.transport.iroh

import computer.iroh.RecvStream
import computer.iroh.SendStream

/**
 * Length-prefixed frames on a canvas relay stream: a 4-byte big-endian length, then that many bytes
 * of UTF-8 JSON (a [com.letta.mobile.data.canvas.CanvasRelayProtocol] frame).
 */
internal object CanvasRelayFraming {
    const val MAX_FRAME_BYTES: Int = 8 * 1024 * 1024
    private const val PREFIX_BYTES = 4

    fun encode(text: String): ByteArray {
        val payload = text.encodeToByteArray()
        require(payload.size <= MAX_FRAME_BYTES) { "Canvas relay frame too large: ${payload.size}" }
        val frame = ByteArray(PREFIX_BYTES + payload.size)
        frame[0] = (payload.size ushr 24).toByte()
        frame[1] = (payload.size ushr 16).toByte()
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = payload.size.toByte()
        payload.copyInto(frame, destinationOffset = PREFIX_BYTES)
        return frame
    }

    suspend fun write(stream: SendStream, text: String) {
        stream.write(encode(text))
    }

    /** The next frame's text, or null when the stream ended or the frame cannot be a valid one. */
    suspend fun read(stream: RecvStream): String? {
        val prefix = ByteArray(PREFIX_BYTES)
        var offset = 0
        while (offset < PREFIX_BYTES) {
            val chunk = stream.read((PREFIX_BYTES - offset).toUInt())
            if (chunk.isEmpty()) return null
            chunk.copyInto(prefix, destinationOffset = offset)
            offset += chunk.size
        }
        val length = ((prefix[0].toInt() and 0xff) shl 24) or
            ((prefix[1].toInt() and 0xff) shl 16) or
            ((prefix[2].toInt() and 0xff) shl 8) or
            (prefix[3].toInt() and 0xff)
        if (length !in 0..MAX_FRAME_BYTES) return null
        return stream.readExact(length.toUInt()).decodeToString()
    }
}
