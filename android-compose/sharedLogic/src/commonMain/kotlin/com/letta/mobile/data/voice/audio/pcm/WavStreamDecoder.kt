package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.PcmFormat

/**
 * Decodes a RIFF WAVE file as it streams in, byte runs of any size in, whole sample frames out.
 * Bytes are held until the `data` chunk header has arrived (at most [headerLimit] of them), then
 * passed through: a sample or frame split across two runs is completed by the next. A `data` size
 * that is a streaming placeholder (0 or 0xFFFFFFFF) means the samples run to the end; a real size
 * is honoured and anything after it ignored. Refusals are thrown as [VoiceAudioException] with
 * [WavCodec]'s codes. Not thread-safe: one decoder per stream.
 */
internal class WavStreamDecoder(private val headerLimit: Int = DEFAULT_HEADER_LIMIT) {
    private var head = ByteArray(0)
    private var format: PcmFormat? = null
    private var remaining: Long? = null
    private var carry = ByteArray(0)

    /** Takes the next run of the stream; returns the whole frames it completes, or null when none. */
    fun push(bytes: ByteArray): PcmChunk? {
        val known = format ?: return header(bytes)
        return frames(known, bytes)
    }

    /** The stream ended. Throws BAD_RESPONSE when it ended before the samples started. */
    fun finish() {
        if (format == null) throw VoiceAudioException(VoiceErrorCode.BAD_RESPONSE)
    }

    private fun header(bytes: ByteArray): PcmChunk? {
        head += bytes
        return when (val read = WavCodec.readHeader(head)) {
            WavHeaderRead.Incomplete -> {
                if (head.size > headerLimit) throw VoiceAudioException(VoiceErrorCode.BAD_RESPONSE)
                null
            }
            is WavHeaderRead.Refused -> throw VoiceAudioException(read.code)
            is WavHeaderRead.Header -> start(read)
        }
    }

    private fun start(read: WavHeaderRead.Header): PcmChunk? {
        format = read.format
        remaining = read.dataBytes
        val rest = head.copyOfRange(read.dataStart.coerceAtMost(head.size), head.size)
        head = ByteArray(0)
        return frames(read.format, rest)
    }

    private fun frames(format: PcmFormat, bytes: ByteArray): PcmChunk? {
        val wanted = remaining?.let { minOf(it, bytes.size.toLong()).toInt() } ?: bytes.size
        remaining = remaining?.minus(wanted)
        val pending = if (carry.isEmpty()) bytes.copyOf(wanted) else carry + bytes.copyOf(wanted)
        val block = format.channels * BYTES_PER_SAMPLE
        val usable = pending.size / block * block
        carry = pending.copyOfRange(usable, pending.size)
        if (usable == 0) return null
        val samples = ShortArray(usable / BYTES_PER_SAMPLE) { index ->
            val low = pending[index * BYTES_PER_SAMPLE].toInt() and BYTE_MASK
            val high = pending[index * BYTES_PER_SAMPLE + 1].toInt() shl BYTE_BITS
            (high or low).toShort()
        }
        return PcmChunk(samples, format)
    }

    private companion object {
        const val DEFAULT_HEADER_LIMIT = 64 * 1024
        const val BYTES_PER_SAMPLE = 2
        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8
    }
}
