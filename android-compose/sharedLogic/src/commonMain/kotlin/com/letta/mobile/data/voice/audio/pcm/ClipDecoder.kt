package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.PcmFormat
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns a synthesised [AudioClip] into PCM a player can write, judging by the bytes rather than
 * trusting the declared encoding (a speech server may ignore `response_format`):
 * - bytes starting with a RIFF header are read as WAVE, whatever the clip claims;
 * - a clip declared [AudioEncoding.PCM] without a container is raw 16-bit little-endian PCM in the
 *   caller's raw format (OpenAI-compatible servers send 24 kHz mono);
 * - compressed audio (MP3, Ogg/Opus, FLAC, AAC) and a declared WAVE without a RIFF header are
 *   refused with a [VoiceAudioException], so noise is never played.
 */
object ClipDecoder {
    private const val RAW_BYTES_PER_SAMPLE = 2

    /** [clip] as PCM; raw PCM is read in [rawPcm]. Throws [VoiceAudioException] when refused. */
    fun decode(clip: AudioClip, rawPcm: PcmFormat): PcmAudio {
        val bytes = clip.bytes
        if (ContainerSniffer.isRiff(bytes)) return wav(bytes)
        val refusal = refusalFor(clip) ?: return raw(bytes, rawPcm)
        throw VoiceAudioException(refusal)
    }

    /** Why a clip without a RIFF header cannot be played; null for playable raw PCM. */
    private fun refusalFor(clip: AudioClip): VoiceErrorCode? = when {
        ContainerSniffer.isCompressed(clip.bytes) -> VoiceErrorCode.UNSUPPORTED_FORMAT
        clip.encoding == AudioEncoding.PCM -> null
        clip.encoding == AudioEncoding.WAV -> VoiceErrorCode.BAD_RESPONSE
        else -> VoiceErrorCode.UNSUPPORTED_FORMAT
    }

    private fun wav(bytes: ByteArray): PcmAudio = when (val parsed = WavCodec.parse(bytes)) {
        is WavParse.Decoded -> parsed.audio
        is WavParse.Refused -> throw VoiceAudioException(parsed.code)
    }

    private fun raw(bytes: ByteArray, format: PcmFormat): PcmAudio {
        val block = format.channels * RAW_BYTES_PER_SAMPLE
        val usable = bytes.size / block * block
        val samples = ShortArray(usable / RAW_BYTES_PER_SAMPLE) { index ->
            val at = index * RAW_BYTES_PER_SAMPLE
            ((bytes[at].toInt() and BYTE_MASK) or (bytes[at + 1].toInt() shl BYTE_BITS)).toShort()
        }
        return PcmAudio(samples, format)
    }

    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
}

/** Recognises containers by their leading bytes. */
internal object ContainerSniffer {
    private val riff = "RIFF".encodeToByteArray()
    private val compressedMagic = listOf("ID3", "OggS", "fLaC", "ADIF").map { it.encodeToByteArray() }

    fun isRiff(bytes: ByteArray): Boolean = bytes.startsWith(riff)

    /**
     * Unambiguous magic of a compressed container: an ID3-tagged MP3, Ogg, FLAC or ADIF AAC. Bare
     * MP3/ADTS frame sync is not checked: it can also be the first sample of raw PCM, and a clip that
     * does not claim to be PCM is refused anyway.
     */
    fun isCompressed(bytes: ByteArray): Boolean = compressedMagic.any { bytes.startsWith(it) }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

/** Loudness of PCM runs, for meters and the avatar's mouth. */
object PcmLevels {
    private const val FULL_SCALE = 32_768.0
    private const val FLOOR_DB = -50.0
    private const val SILENCE_DB = -120.0
    private const val DB_PER_DECADE = 20.0

    /** The RMS of [samples] in dBFS; digital silence (or no samples) is -120. */
    fun dbfs(samples: ShortArray): Double {
        if (samples.isEmpty()) return SILENCE_DB
        val meanSquare = samples.sumOf { it.toDouble() * it } / samples.size
        val rms = sqrt(meanSquare) / FULL_SCALE
        return if (rms <= 0.0) SILENCE_DB else DB_PER_DECADE * log10(rms)
    }

    /** The loudness of [samples] on a 50 dB scale: -50 dBFS and below is 0, full scale is 1. */
    fun level(samples: ShortArray): AudioLevel = AudioLevel.clamped(((dbfs(samples) - FLOOR_DB) / -FLOOR_DB).toFloat())
}
