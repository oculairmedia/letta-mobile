package com.letta.mobile.data.transport.iroh

import computer.iroh.RecvStream
import computer.iroh.SendStream

/**
 * Length-delimited framing for App Server JSON payloads over Iroh streams.
 *
 * Wire format (plain frame, unchanged since v0):
 * - 4-byte unsigned length prefix, big-endian network byte order (< [PART_SENTINEL])
 * - exactly [length] UTF-8 JSON payload bytes
 *
 * Wire format (`frame_part` continuation, capability-gated):
 * A logical frame larger than [DEFAULT_MAX_FRAME_BYTES] is split into ordered
 * parts. Each part on the wire is:
 * - 4-byte sentinel prefix [PART_SENTINEL] (0x7FFFFFFF, big-endian). Peers
 *   without the `frame_part` capability decode this as an oversized plain
 *   frame length and reject it with a clean [ProtocolException] instead of
 *   corrupting the stream — which is why senders must only emit parts to
 *   peers that advertised [FRAME_PART_CAPABILITY] during the auth handshake.
 * - 1-byte flags: bit 0 ([PART_FLAG_FIN]) set on the final part
 * - 4-byte part index, big-endian, 0-based, strictly sequential
 * - 4-byte part payload length, big-endian, 1..maxFrameBytes
 * - exactly [part payload length] payload bytes
 *
 * Parts of one logical frame are strictly sequential on their QUIC stream
 * (each decoder owns exactly one ordered byte stream; writers hold a per-stream
 * mutex), so reassembly never needs to tolerate interleaving: any plain frame
 * or out-of-sequence index mid-reassembly is a protocol error. Reassembly
 * memory is bounded by [DEFAULT_MAX_REASSEMBLED_BYTES]; oversized logical
 * frames are rejected with the typed [FrameTooLargeException].
 *
 * The App Server JSON protocol is unchanged; only the stream framing changes.
 */
object IrohFrameCodec {
    const val DEFAULT_MAX_FRAME_BYTES: Int = 1_048_576 // 1 MiB per frame / per part
    const val DEFAULT_MAX_REASSEMBLED_BYTES: Int = 33_554_432 // 32 MiB logical frame cap

    /** Capability string advertised in the auth handshake (`capabilities` array). */
    const val FRAME_PART_CAPABILITY: String = "frame_part"

    private const val LENGTH_PREFIX_BYTES = 4
    private const val PART_SENTINEL = 0x7FFFFFFF
    private const val PART_HEADER_BYTES = 9 // flags(1) + part_index(4) + part_len(4)
    private const val PART_FLAG_FIN = 0x01

    open class ProtocolException(message: String) : IllegalArgumentException(message)

    /** Typed rejection for frames/reassemblies exceeding the configured bounds. */
    class FrameTooLargeException(message: String) : ProtocolException(message)

    fun encodeFrame(frame: String, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES): ByteArray =
        encodeFrame(frame.encodeToByteArray(), maxFrameBytes)

    fun encodeFrame(payload: ByteArray, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES): ByteArray {
        if (payload.size > maxFrameBytes) {
            throw FrameTooLargeException("Iroh frame too large: ${payload.size} bytes > max $maxFrameBytes")
        }
        val out = ByteArray(LENGTH_PREFIX_BYTES + payload.size)
        encodeInt(out, 0, payload.size)
        payload.copyInto(out, destinationOffset = LENGTH_PREFIX_BYTES)
        return out
    }

    /**
     * Encodes [frame] as one plain frame when it fits in [maxFrameBytes], or as an
     * ordered list of `frame_part` continuation frames otherwise. Only send the
     * multi-part encoding to peers that advertised [FRAME_PART_CAPABILITY].
     */
    fun encodeFrameParts(
        frame: String,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
        maxTotalBytes: Int = DEFAULT_MAX_REASSEMBLED_BYTES,
    ): List<ByteArray> = encodeFrameParts(frame.encodeToByteArray(), maxFrameBytes, maxTotalBytes)

    fun encodeFrameParts(
        payload: ByteArray,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
        maxTotalBytes: Int = DEFAULT_MAX_REASSEMBLED_BYTES,
    ): List<ByteArray> {
        if (payload.size <= maxFrameBytes) return listOf(encodeFrame(payload, maxFrameBytes))
        if (payload.size > maxTotalBytes) {
            throw FrameTooLargeException("Iroh logical frame too large: ${payload.size} bytes > max $maxTotalBytes")
        }
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        var index = 0
        while (offset < payload.size) {
            val partLen = minOf(maxFrameBytes, payload.size - offset)
            val fin = offset + partLen == payload.size
            val out = ByteArray(LENGTH_PREFIX_BYTES + PART_HEADER_BYTES + partLen)
            encodeInt(out, 0, PART_SENTINEL)
            out[4] = if (fin) PART_FLAG_FIN.toByte() else 0
            encodeInt(out, 5, index)
            encodeInt(out, 9, partLen)
            payload.copyInto(out, destinationOffset = LENGTH_PREFIX_BYTES + PART_HEADER_BYTES, startIndex = offset, endIndex = offset + partLen)
            parts += out
            offset += partLen
            index++
        }
        return parts
    }

    class Decoder(
        private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
        /**
         * Upper bound for `frame_part` reassembly, evaluated per part header so
         * callers can widen it dynamically (e.g. servers only grant the full
         * [DEFAULT_MAX_REASSEMBLED_BYTES] once the peer has authenticated and
         * advertised the capability; before that, unauthenticated peers must
         * not be able to hold more than a plain frame's worth of memory).
         */
        private val maxReassembledBytesProvider: () -> Int = { DEFAULT_MAX_REASSEMBLED_BYTES },
    ) {
        constructor(
            maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
            maxReassembledBytes: Int,
        ) : this(maxFrameBytes, { maxReassembledBytes })

        private var expectedLength: Int? = null
        private val prefix = ByteArray(LENGTH_PREFIX_BYTES)
        private var prefixBytes = 0
        private var payload = ByteArray(0)
        private var payloadBytes = 0

        // frame_part reassembly state (strictly sequential; no interleaving on a stream)
        private var readingPartHeader = false
        private val partHeader = ByteArray(PART_HEADER_BYTES)
        private var partHeaderBytes = 0
        private var currentSegmentIsPart = false
        private var currentPartFin = false
        private var expectedPartIndex = 0
        private val pendingParts = mutableListOf<ByteArray>()
        private var pendingBytes = 0

        fun feed(chunk: ByteArray): List<String> {
            if (chunk.isEmpty()) return emptyList()
            val frames = mutableListOf<String>()
            var offset = 0
            while (offset < chunk.size) {
                val length = expectedLength
                if (readingPartHeader) {
                    val toCopy = minOf(PART_HEADER_BYTES - partHeaderBytes, chunk.size - offset)
                    chunk.copyInto(partHeader, destinationOffset = partHeaderBytes, startIndex = offset, endIndex = offset + toCopy)
                    partHeaderBytes += toCopy
                    offset += toCopy
                    if (partHeaderBytes == PART_HEADER_BYTES) {
                        beginPartPayload()
                    }
                } else if (length == null) {
                    val toCopy = minOf(LENGTH_PREFIX_BYTES - prefixBytes, chunk.size - offset)
                    chunk.copyInto(prefix, destinationOffset = prefixBytes, startIndex = offset, endIndex = offset + toCopy)
                    prefixBytes += toCopy
                    offset += toCopy
                    if (prefixBytes == LENGTH_PREFIX_BYTES) {
                        prefixBytes = 0
                        val decodedLength = decodeInt(prefix, 0)
                        if (decodedLength == PART_SENTINEL) {
                            readingPartHeader = true
                            partHeaderBytes = 0
                        } else {
                            if (reassemblyInProgress()) {
                                throw ProtocolException(
                                    "Plain Iroh frame interleaved mid frame_part sequence (expected part $expectedPartIndex)"
                                )
                            }
                            if (decodedLength !in 0..maxFrameBytes) {
                                throw ProtocolException("Iroh frame too large: $decodedLength bytes > max $maxFrameBytes")
                            }
                            currentSegmentIsPart = false
                            expectedLength = decodedLength
                            payload = ByteArray(decodedLength)
                            payloadBytes = 0
                            if (decodedLength == 0) {
                                frames += ""
                                resetSegment()
                            }
                        }
                    }
                } else {
                    val toCopy = minOf(length - payloadBytes, chunk.size - offset)
                    chunk.copyInto(payload, destinationOffset = payloadBytes, startIndex = offset, endIndex = offset + toCopy)
                    payloadBytes += toCopy
                    offset += toCopy
                    if (payloadBytes == length) {
                        if (currentSegmentIsPart) {
                            pendingParts += payload
                            pendingBytes += payload.size
                            expectedPartIndex++
                            val fin = currentPartFin
                            resetSegment()
                            if (fin) {
                                frames += drainPendingParts()
                            }
                        } else {
                            frames += payload.decodeToString()
                            resetSegment()
                        }
                    }
                }
            }
            return frames
        }

        fun finish() {
            if (prefixBytes != 0 || expectedLength != null || readingPartHeader || reassemblyInProgress()) {
                throw ProtocolException("Truncated Iroh frame: stream ended mid-frame")
            }
        }

        private fun reassemblyInProgress(): Boolean = pendingParts.isNotEmpty() || expectedPartIndex != 0

        private fun beginPartPayload() {
            readingPartHeader = false
            partHeaderBytes = 0
            val flags = partHeader[0].toInt() and 0xff
            val index = decodeInt(partHeader, 1)
            val partLen = decodeInt(partHeader, 5)
            if (flags and PART_FLAG_FIN.inv() != 0) {
                throw ProtocolException("Unknown frame_part flags: 0x${flags.toString(16)}")
            }
            if (index != expectedPartIndex) {
                throw ProtocolException("Out-of-sequence frame_part: expected part $expectedPartIndex, received part $index")
            }
            if (partLen !in 1..maxFrameBytes) {
                throw ProtocolException("Invalid frame_part payload length: $partLen bytes (max $maxFrameBytes)")
            }
            val maxReassembledBytes = maxReassembledBytesProvider()
            if (pendingBytes.toLong() + partLen > maxReassembledBytes) {
                throw FrameTooLargeException(
                    "Reassembled Iroh frame too large: ${pendingBytes.toLong() + partLen} bytes > max $maxReassembledBytes"
                )
            }
            currentSegmentIsPart = true
            currentPartFin = (flags and PART_FLAG_FIN) != 0
            expectedLength = partLen
            payload = ByteArray(partLen)
            payloadBytes = 0
        }

        private fun drainPendingParts(): String {
            val out = ByteArray(pendingBytes)
            var position = 0
            pendingParts.forEach { part ->
                part.copyInto(out, destinationOffset = position)
                position += part.size
            }
            pendingParts.clear()
            pendingBytes = 0
            expectedPartIndex = 0
            return out.decodeToString()
        }

        private fun resetSegment() {
            expectedLength = null
            payload = ByteArray(0)
            payloadBytes = 0
            currentSegmentIsPart = false
            currentPartFin = false
        }
    }

    suspend fun write(
        sendStream: SendStream,
        frame: String,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
        allowFrameParts: Boolean = false,
    ) {
        if (allowFrameParts) {
            encodeFrameParts(frame, maxFrameBytes).forEach { sendStream.writeAll(it) }
        } else {
            sendStream.writeAll(encodeFrame(frame, maxFrameBytes))
        }
    }

    suspend fun readOne(
        recvStream: RecvStream,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
        chunkBytes: Int = 8192,
        maxReassembledBytes: Int = DEFAULT_MAX_REASSEMBLED_BYTES,
    ): String? {
        val decoder = Decoder(maxFrameBytes, maxReassembledBytes)
        while (true) {
            val chunk = recvStream.read(chunkBytes.toUInt())
            if (chunk.isEmpty()) {
                decoder.finish()
                return null
            }
            val frames = decoder.feed(chunk)
            if (frames.isNotEmpty()) return frames.first()
        }
    }

    suspend fun readAll(
        recvStream: RecvStream,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
        chunkBytes: Int = 8192,
        maxReassembledBytes: Int = DEFAULT_MAX_REASSEMBLED_BYTES,
        /** When set, overrides [maxReassembledBytes] with a per-part dynamic bound. */
        maxReassembledBytesProvider: (() -> Int)? = null,
        onFrame: suspend (String) -> Unit,
    ) {
        val decoder = Decoder(maxFrameBytes, maxReassembledBytesProvider ?: { maxReassembledBytes })
        while (true) {
            val chunk = recvStream.read(chunkBytes.toUInt())
            if (chunk.isEmpty()) break
            decoder.feed(chunk).forEach { onFrame(it) }
        }
        decoder.finish()
    }

    private fun encodeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xff).toByte()
        target[offset + 1] = ((value ushr 16) and 0xff).toByte()
        target[offset + 2] = ((value ushr 8) and 0xff).toByte()
        target[offset + 3] = (value and 0xff).toByte()
    }

    private fun decodeInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)
}
