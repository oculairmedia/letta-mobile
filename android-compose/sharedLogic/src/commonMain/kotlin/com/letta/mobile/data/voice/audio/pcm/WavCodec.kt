package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.PcmFormat

/** The outcome of reading a RIFF WAVE file. */
sealed interface WavParse {
    /** The file held 16-bit integer PCM. */
    data class Decoded(
        /** The samples. */
        val audio: PcmAudio,
    ) : WavParse

    /**
     * The file was refused: BAD_RESPONSE when it is not a readable WAVE file (no RIFF header, no
     * `fmt ` before `data`, a header cut short), UNSUPPORTED_FORMAT when it is valid but not 16-bit
     * integer PCM at a sample rate from 1 Hz to 384 kHz.
     */
    data class Refused(
        /** Why. */
        val code: VoiceErrorCode,
    ) : WavParse
}

/**
 * Reads and writes RIFF WAVE. Reading skips unknown chunks (LIST, fact, cue), honours odd-size
 * chunk padding, accepts WAVE_FORMAT_EXTENSIBLE with a PCM sub-format and tolerates a `data`
 * chunk whose declared size is a streaming placeholder (0 or 0xFFFFFFFF) or runs past the end:
 * the bytes that are present are used, cut to whole sample frames.
 */
object WavCodec {
    private const val RIFF_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val FMT_MIN = 16
    private const val EXTENSIBLE_MIN = 40
    private const val SUB_FORMAT_OFFSET = 24
    private const val FORMAT_EXTENSIBLE = 0xFFFE
    private const val BYTES_PER_SAMPLE = 2
    private const val PLACEHOLDER_SIZE = 0xFFFFFFFFL
    private const val HEADER_BYTES = 44
    private const val RIFF_TAG = "RIFF"

    /** Reads [bytes] as a WAVE file. */
    fun parse(bytes: ByteArray): WavParse {
        val reader = LittleEndianReader(bytes)
        if (!reader.hasRiffWave()) return WavParse.Refused(VoiceErrorCode.BAD_RESPONSE)
        val walk = reader.walkToData()
        if (walk !is WavWalk.Found) return WavParse.Refused(VoiceErrorCode.BAD_RESPONSE)
        return decode(reader, walk.fmt, walk.data)
    }

    /**
     * Reads the start of a WAVE stream, [bytes] being everything received so far: the format and
     * where the samples start once the `data` chunk header is in, [WavHeaderRead.Incomplete] while
     * it may still arrive, refused as [parse] would refuse it otherwise. A `data` size that is a
     * streaming placeholder (0 or 0xFFFFFFFF) means the samples run to the end of the stream.
     */
    internal fun readHeader(bytes: ByteArray): WavHeaderRead {
        val reader = LittleEndianReader(bytes)
        if (bytes.size < RIFF_HEADER) {
            val riffSoFar = bytes.indices.all { it >= RIFF_TAG.length || bytes[it] == RIFF_TAG[it].code.toByte() }
            return if (riffSoFar) WavHeaderRead.Incomplete else WavHeaderRead.Refused(VoiceErrorCode.BAD_RESPONSE)
        }
        if (!reader.hasRiffWave()) return WavHeaderRead.Refused(VoiceErrorCode.BAD_RESPONSE)
        return when (val walk = reader.walkToData()) {
            WavWalk.Truncated -> WavHeaderRead.Incomplete
            WavWalk.Malformed -> WavHeaderRead.Refused(VoiceErrorCode.BAD_RESPONSE)
            is WavWalk.Found -> headerOf(walk)
        }
    }

    private fun headerOf(walk: WavWalk.Found): WavHeaderRead {
        val format = walk.fmt ?: return WavHeaderRead.Refused(VoiceErrorCode.BAD_RESPONSE)
        if (!format.isSupported()) return WavHeaderRead.Refused(VoiceErrorCode.UNSUPPORTED_FORMAT)
        val size = walk.data.size
        val dataBytes = if (size == 0L || size == PLACEHOLDER_SIZE) null else size
        val pcm = PcmFormat(format.sampleRateHz.toInt(), format.channels)
        return WavHeaderRead.Header(pcm, walk.data.body.toInt(), dataBytes)
    }

    /** Walks the chunks up to the `data` chunk header, remembering the last `fmt `. */
    private fun LittleEndianReader.walkToData(): WavWalk {
        var offset = RIFF_HEADER.toLong()
        var fmt: WavFmt? = null
        while (offset + CHUNK_HEADER <= size) {
            val chunk = chunkAt(offset.toInt())
            if (chunk.id == "data") return WavWalk.Found(fmt, chunk)
            if (chunk.id == "fmt ") {
                fmt = fmtOf(chunk) ?: return if (chunk.body + chunk.size > size) WavWalk.Truncated else WavWalk.Malformed
            }
            offset = chunk.body + chunk.size + (chunk.size and 1L)
        }
        return WavWalk.Truncated
    }

    /** [audio] as a canonical 44-byte-header WAVE file. */
    fun encode(audio: PcmAudio): ByteArray {
        val dataBytes = audio.samples.size * BYTES_PER_SAMPLE
        val out = LittleEndianWriter(ByteArray(HEADER_BYTES + dataBytes))
        val format = audio.format
        out.tag("RIFF").u32(HEADER_BYTES - CHUNK_HEADER + dataBytes).tag("WAVE")
        out.tag("fmt ").u32(FMT_MIN).u16(FORMAT_PCM).u16(format.channels).u32(format.sampleRateHz)
        out.u32(format.sampleRateHz * format.channels * BYTES_PER_SAMPLE).u16(format.channels * BYTES_PER_SAMPLE).u16(BITS_16)
        out.tag("data").u32(dataBytes)
        audio.samples.forEach { out.u16(it.toInt()) }
        return out.bytes
    }

    private fun decode(reader: LittleEndianReader, fmt: WavFmt?, chunk: WavChunk): WavParse {
        val format = fmt ?: return WavParse.Refused(VoiceErrorCode.BAD_RESPONSE)
        if (!format.isSupported()) return WavParse.Refused(VoiceErrorCode.UNSUPPORTED_FORMAT)
        val present = reader.size - chunk.body
        val declared = if (chunk.size == 0L || chunk.size == PLACEHOLDER_SIZE) present else chunk.size
        val block = format.channels * BYTES_PER_SAMPLE
        val usable = minOf(declared, present) / block * block
        val samples = ShortArray((usable / BYTES_PER_SAMPLE).toInt()) { reader.s16(chunk.body.toInt() + it * BYTES_PER_SAMPLE) }
        return WavParse.Decoded(PcmAudio(samples, PcmFormat(format.sampleRateHz.toInt(), format.channels)))
    }

    private fun LittleEndianReader.hasRiffWave(): Boolean {
        if (size < RIFF_HEADER) return false
        return tag(0) == "RIFF" && tag(RIFF_HEADER - 4) == "WAVE"
    }

    private fun LittleEndianReader.chunkAt(offset: Int): WavChunk =
        WavChunk(tag(offset), u32(offset + 4), (offset + CHUNK_HEADER).toLong())

    private fun LittleEndianReader.fmtOf(chunk: WavChunk): WavFmt? {
        val at = chunk.body.toInt()
        if (chunk.size < FMT_MIN || at + FMT_MIN > size) return null
        val tag = u16(at)
        val effective = if (tag == FORMAT_EXTENSIBLE) extensibleTag(chunk) else tag
        if (effective < 0) return null
        return WavFmt(effective, u16(at + 2), u32(at + 4), u16(at + 14))
    }

    /**
     * PCM when an extensible header's whole 16-byte sub-format GUID is the PCM GUID, the extensible
     * tag (refused later as unsupported) for any other GUID, and -1 when the header is cut short.
     */
    private fun LittleEndianReader.extensibleTag(chunk: WavChunk): Int {
        val subFormat = chunk.body.toInt() + SUB_FORMAT_OFFSET
        val complete = chunk.size >= EXTENSIBLE_MIN && subFormat + PCM_SUB_FORMAT.size <= size
        if (!complete) return -1
        val isPcm = PCM_SUB_FORMAT.indices.all { u8(subFormat + it) == PCM_SUB_FORMAT[it] }
        return if (isPcm) FORMAT_PCM else FORMAT_EXTENSIBLE
    }
}

/** What the start of a WAVE stream says so far. */
internal sealed interface WavHeaderRead {
    /** The `data` chunk header has not arrived yet. */
    data object Incomplete : WavHeaderRead

    /** The stream is refused: BAD_RESPONSE or UNSUPPORTED_FORMAT, as [WavCodec.parse] would. */
    data class Refused(val code: VoiceErrorCode) : WavHeaderRead

    /** Samples in [format] start at byte [dataStart]; [dataBytes] of them, or to the end of the stream when null. */
    class Header(val format: PcmFormat, val dataStart: Int, val dataBytes: Long?) : WavHeaderRead
}

/** Where a walk over the chunks ended: at the `data` chunk, short of it, or at a malformed `fmt `. */
private sealed interface WavWalk {
    data object Truncated : WavWalk

    data object Malformed : WavWalk

    class Found(val fmt: WavFmt?, val data: WavChunk) : WavWalk
}

private class WavChunk(val id: String, val size: Long, val body: Long)

private const val FORMAT_PCM = 1
private const val BITS_16 = 16
private const val MAX_CHANNELS = 8
private const val MAX_SAMPLE_RATE_HZ = 384_000L

/** A `fmt ` chunk; the rate stays unsigned (a Long) until it has been checked. */
private class WavFmt(val formatTag: Int, val channels: Int, val sampleRateHz: Long, val bitsPerSample: Int) {
    /** 16-bit integer PCM, 1 to 8 channels, 1 Hz to 384 kHz. */
    fun isSupported(): Boolean {
        val pcm16 = formatTag == FORMAT_PCM && bitsPerSample == BITS_16
        return pcm16 && channels in 1..MAX_CHANNELS && sampleRateHz in 1L..MAX_SAMPLE_RATE_HZ
    }
}

/** KSDATAFORMAT_SUBTYPE_PCM, {00000001-0000-0010-8000-00aa00389b71}, as it is laid out in the file. */
private val PCM_SUB_FORMAT = intArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71)

private class LittleEndianReader(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun u8(at: Int): Int = bytes[at].toInt() and BYTE_MASK

    fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl BYTE_BITS)

    fun s16(at: Int): Short = u16(at).toShort()

    fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl SHORT_BITS)

    fun tag(at: Int): String = CharArray(TAG_LENGTH) { u8(at + it).toChar() }.concatToString()

    private companion object {
        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8
        const val SHORT_BITS = 16
        const val TAG_LENGTH = 4
    }
}

private class LittleEndianWriter(val bytes: ByteArray) {
    private var at = 0

    fun tag(value: String): LittleEndianWriter = apply { value.forEach { bytes[at++] = it.code.toByte() } }

    fun u16(value: Int): LittleEndianWriter = apply {
        bytes[at++] = value.toByte()
        bytes[at++] = (value shr BYTE_BITS).toByte()
    }

    fun u32(value: Int): LittleEndianWriter = u16(value and SHORT_MASK).u16(value ushr SHORT_BITS)

    private companion object {
        const val BYTE_BITS = 8
        const val SHORT_BITS = 16
        const val SHORT_MASK = 0xFFFF
    }
}
