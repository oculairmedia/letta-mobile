package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.PcmFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WavStreamDecoderTest {
    private val samples = ShortArray(9) { (it * 1_111 - 4_000).toShort() }

    /** A canonical WAVE file of [samples]; with [placeholder], its RIFF and data sizes are 0xFFFFFFFF. */
    private fun wav(samples: ShortArray, format: PcmFormat = PcmFormat(24_000), placeholder: Boolean = true): ByteArray {
        val bytes = WavCodec.encode(PcmAudio(samples, format))
        if (placeholder) for (field in listOf(4, 40)) for (byte in 0 until 4) bytes[field + byte] = 0xFF.toByte()
        return bytes
    }

    private fun WavStreamDecoder.decodeAll(bytes: ByteArray, runs: List<Int>): List<Short> {
        val out = mutableListOf<Short>()
        var at = 0
        for (size in runs + (bytes.size)) {
            val end = minOf(at + size, bytes.size)
            push(bytes.copyOfRange(at, end))?.let { out += it.samples.toList() }
            at = end
            if (at == bytes.size) break
        }
        finish()
        return out
    }

    @Test
    fun aStreamedWaveWithPlaceholderSizesDecodesEverySampleAtItsRate() {
        val decoder = WavStreamDecoder()
        val chunk = decoder.push(wav(samples))
        assertEquals(PcmFormat(24_000), chunk?.format)
        assertContentEquals(samples, chunk?.samples)
    }

    @Test
    fun aSampleSplitAcrossRunsIsReassembled() {
        val bytes = wav(samples)
        val decoder = WavStreamDecoder()
        assertNull(decoder.push(bytes.copyOfRange(0, 45)), "header plus half a sample yields nothing yet")
        val rest = decoder.push(bytes.copyOfRange(45, bytes.size))
        assertContentEquals(samples, rest?.samples)
    }

    @Test
    fun anyRunBoundaryYieldsTheSameSamples() {
        val bytes = wav(samples)
        listOf(listOf(1), listOf(3, 5, 7), listOf(43, 1, 1, 1), listOf(44, 3, 3)).forEach { runs ->
            val decoded = WavStreamDecoder().decodeAll(bytes, List(bytes.size) { runs[it % runs.size] })
            assertEquals(samples.toList(), decoded, "runs $runs")
        }
    }

    @Test
    fun aSplitStereoFrameWaitsForItsLastByte() {
        val stereo = ShortArray(4) { (it + 1).toShort() }
        val bytes = wav(stereo, PcmFormat(16_000, channels = 2))
        val decoder = WavStreamDecoder()
        assertNull(decoder.push(bytes.copyOfRange(0, 44 + 3)))
        val first = decoder.push(bytes.copyOfRange(44 + 3, 44 + 5))
        assertContentEquals(shortArrayOf(1, 2), first?.samples)
        assertContentEquals(shortArrayOf(3, 4), decoder.push(bytes.copyOfRange(44 + 5, bytes.size))?.samples)
    }

    @Test
    fun aRealDataSizeIsHonouredAndTrailingBytesIgnored() {
        val bytes = wav(samples, placeholder = false) + byteArrayOf(9, 9, 9, 9)
        val decoded = WavStreamDecoder().decodeAll(bytes, listOf(50))
        assertEquals(samples.toList(), decoded)
    }

    @Test
    fun compressedAudioIsRefusedAndAStreamThatEndsInTheHeaderFails() {
        val mp3 = assertFailsWith<VoiceAudioException> { WavStreamDecoder().push("ID3".encodeToByteArray() + ByteArray(64)) }
        assertEquals(VoiceErrorCode.BAD_RESPONSE, mp3.code)
        val cut = WavStreamDecoder()
        assertNull(cut.push(wav(samples).copyOfRange(0, 30)))
        assertEquals(VoiceErrorCode.BAD_RESPONSE, assertFailsWith<VoiceAudioException> { cut.finish() }.code)
    }

    @Test
    fun aHeaderThatNeverReachesItsSamplesIsRefusedOnceTooLong() {
        val decoder = WavStreamDecoder(headerLimit = 64)
        val riff = wav(ShortArray(0)).copyOfRange(0, 12)
        val junk = "junk".encodeToByteArray() + byteArrayOf(0, 1, 0, 0)
        assertNull(decoder.push(riff + junk))
        val refused = assertFailsWith<VoiceAudioException> { decoder.push(ByteArray(64)) }
        assertEquals(VoiceErrorCode.BAD_RESPONSE, refused.code)
    }

    @Test
    fun eightBitAudioIsRefusedAsUnsupported() {
        val bytes = wav(samples)
        bytes[34] = 8
        val refused = assertFailsWith<VoiceAudioException> { WavStreamDecoder().push(bytes) }
        assertEquals(VoiceErrorCode.UNSUPPORTED_FORMAT, refused.code)
    }
}
