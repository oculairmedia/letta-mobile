package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.PcmFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/** A `fmt ` chunk body. */
private data class FmtSpec(val tag: Int = 1, val channels: Int = 1, val rate: Int = 16_000, val bits: Int = 16)

/** One RIFF chunk; [declared] overrides the size field (streaming placeholders, truncation). */
private class Chunk(val id: String, val body: ByteArray, val declared: Long = body.size.toLong())

private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

private fun le32(value: Long) = le16((value and 0xFFFF).toInt()) + le16(((value shr 16) and 0xFFFF).toInt())

private fun fmtBody(spec: FmtSpec): ByteArray {
    val block = spec.channels * spec.bits / 8
    val rates = le32(spec.rate.toLong()) + le32(spec.rate.toLong() * block)
    return le16(spec.tag) + le16(spec.channels) + rates + le16(block) + le16(spec.bits)
}

private fun extensibleFmt(subFormat: Int): ByteArray =
    fmtBody(FmtSpec(tag = 0xFFFE)) + le16(22) + le16(16) + le32(4) + le16(subFormat) + ByteArray(14)

private fun samplesBody(vararg samples: Int): ByteArray = samples.fold(ByteArray(0)) { bytes, sample -> bytes + le16(sample) }

private fun riff(vararg chunks: Chunk): ByteArray {
    val body = chunks.fold("WAVE".encodeToByteArray()) { bytes, chunk ->
        val padding = if (chunk.body.size % 2 == 1) ByteArray(1) else ByteArray(0)
        bytes + chunk.id.encodeToByteArray() + le32(chunk.declared) + chunk.body + padding
    }
    return "RIFF".encodeToByteArray() + le32(body.size.toLong()) + body
}

/** What a case should parse to: samples and channels, or a refusal code. */
private sealed interface Expect {
    data class Samples(val samples: List<Int>, val channels: Int = 1) : Expect

    data class Refused(val code: VoiceErrorCode) : Expect
}

private class WavCase(val name: String, val bytes: ByteArray, val expect: Expect)

class WavCodecTest {
    private val fmt = Chunk("fmt ", fmtBody(FmtSpec()))

    private val badResponse = Expect.Refused(VoiceErrorCode.BAD_RESPONSE)
    private val unsupported = Expect.Refused(VoiceErrorCode.UNSUPPORTED_FORMAT)

    private fun data(vararg samples: Int, declared: Long? = null): Chunk {
        val body = samplesBody(*samples)
        return Chunk("data", body, declared ?: body.size.toLong())
    }

    private fun fmtOf(spec: FmtSpec) = Chunk("fmt ", fmtBody(spec))

    private val cases = listOf(
        WavCase("valid mono", riff(fmt, data(1, -2, 3)), Expect.Samples(listOf(1, -2, 3))),
        WavCase(
            "extra chunks and odd padding are skipped",
            riff(Chunk("LIST", ByteArray(5)), fmt, Chunk("junk", ByteArray(3)), data(7, 8)),
            Expect.Samples(listOf(7, 8)),
        ),
        WavCase("extensible header with a PCM sub-format", riff(Chunk("fmt ", extensibleFmt(1)), data(5)), Expect.Samples(listOf(5))),
        WavCase("stereo", riff(fmtOf(FmtSpec(channels = 2)), data(1, 2)), Expect.Samples(listOf(1, 2), 2)),
        WavCase("data cut short uses what is present", riff(fmt, data(4, 5, 6, declared = 100)), Expect.Samples(listOf(4, 5, 6))),
        WavCase(
            "odd trailing byte is dropped",
            riff(fmt, Chunk("data", samplesBody(4) + byteArrayOf(9), declared = 3)),
            Expect.Samples(listOf(4)),
        ),
        WavCase("streaming placeholder size", riff(fmt, data(9, 9, declared = 0xFFFFFFFFL)), Expect.Samples(listOf(9, 9))),
        WavCase("zero placeholder size", riff(fmt, data(3, declared = 0)), Expect.Samples(listOf(3))),
        WavCase("header cut short", riff(fmt).copyOf(20), badResponse),
        WavCase("not RIFF", "ID3xxxxxxxxxxxxx".encodeToByteArray(), badResponse),
        WavCase("data before fmt", riff(data(1), fmt), badResponse),
        WavCase("no data chunk", riff(fmt), badResponse),
        WavCase("fmt chunk too short", riff(Chunk("fmt ", ByteArray(8)), data(1)), badResponse),
        WavCase("32-bit float", riff(fmtOf(FmtSpec(tag = 3, bits = 32)), Chunk("data", ByteArray(8))), unsupported),
        WavCase("8-bit PCM", riff(fmtOf(FmtSpec(bits = 8)), Chunk("data", ByteArray(4))), unsupported),
        WavCase("extensible float sub-format", riff(Chunk("fmt ", extensibleFmt(3)), Chunk("data", ByteArray(4))), unsupported),
    )

    @Test
    fun parsesEveryCase() {
        cases.forEach { case ->
            val parsed = WavCodec.parse(case.bytes)
            when (val expect = case.expect) {
                is Expect.Samples -> {
                    val audio = (parsed as? WavParse.Decoded)?.audio ?: fail("${case.name}: $parsed")
                    assertEquals(expect.samples, audio.samples.map { it.toInt() }, case.name)
                    assertEquals(expect.channels, audio.format.channels, case.name)
                }
                is Expect.Refused -> assertEquals(WavParse.Refused(expect.code), parsed, case.name)
            }
        }
    }

    @Test
    fun encodeThenParseRoundTrips() {
        val audio = PcmAudio(shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE), PcmFormat(22_050, 1))
        val bytes = WavCodec.encode(audio)
        assertEquals(44 + 10, bytes.size)
        val decoded = (WavCodec.parse(bytes) as WavParse.Decoded).audio
        assertContentEquals(audio.samples, decoded.samples)
        assertEquals(audio.format, decoded.format)
    }
}

private class DecodeCase(val name: String, val clip: AudioClip, val expect: Expect)

class ClipDecoderTest {
    private val wav = WavCodec.encode(PcmAudio(shortArrayOf(1, 2), PcmFormat(16_000)))
    private val rawPcm = PcmFormat(24_000)

    private fun tagged(magic: String, encoding: AudioEncoding) = AudioClip(magic.encodeToByteArray() + ByteArray(20), encoding)

    private val unsupported = Expect.Refused(VoiceErrorCode.UNSUPPORTED_FORMAT)

    private val cases = listOf(
        DecodeCase("WAVE clip", AudioClip(wav, AudioEncoding.WAV), Expect.Samples(listOf(1, 2))),
        DecodeCase("WAVE bytes despite an MP3 label", AudioClip(wav, AudioEncoding.MP3), Expect.Samples(listOf(1, 2))),
        DecodeCase("raw PCM", AudioClip(samplesBody(3, -4) + byteArrayOf(1), AudioEncoding.PCM), Expect.Samples(listOf(3, -4))),
        DecodeCase("MP3", tagged("ID3", AudioEncoding.MP3), unsupported),
        DecodeCase("Ogg labelled PCM", tagged("OggS", AudioEncoding.PCM), unsupported),
        DecodeCase("FLAC labelled PCM", tagged("fLaC", AudioEncoding.PCM), unsupported),
        DecodeCase("opus without magic", AudioClip(ByteArray(20), AudioEncoding.OPUS), unsupported),
        DecodeCase("WAVE label without RIFF", AudioClip(ByteArray(20), AudioEncoding.WAV), Expect.Refused(VoiceErrorCode.BAD_RESPONSE)),
    )

    @Test
    fun decodesOrRefusesEveryCase() {
        cases.forEach { case ->
            when (val expect = case.expect) {
                is Expect.Samples -> {
                    val audio = ClipDecoder.decode(case.clip, rawPcm)
                    assertEquals(expect.samples, audio.samples.map { it.toInt() }, case.name)
                }
                is Expect.Refused -> {
                    val error = assertFailsWith<VoiceAudioException>(case.name) { ClipDecoder.decode(case.clip, rawPcm) }
                    assertEquals(expect.code, error.code, case.name)
                }
            }
        }
    }

    @Test
    fun rawPcmTakesTheCallersFormat() {
        assertEquals(rawPcm, ClipDecoder.decode(AudioClip(samplesBody(1), AudioEncoding.PCM), rawPcm).format)
    }

    @Test
    fun levelsSpanFiftyDecibels() {
        assertEquals(0f, PcmLevels.level(ShortArray(100)).value)
        assertEquals(1f, PcmLevels.level(ShortArray(100) { Short.MAX_VALUE }).value, 0.001f)
        assertEquals(0.6f, PcmLevels.level(ShortArray(100) { 3277 }).value, 0.01f)
    }
}
