package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import kotlinx.coroutines.flow.flowOf
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns a synthesised [AudioClip] into PCM a player can write, judging by the bytes rather than
 * trusting the declared encoding (a speech server may ignore `response_format`): bytes starting with
 * a RIFF header are read as WAVE, whatever the clip claims. Everything else is refused with a
 * [VoiceAudioException], so noise is never played: compressed audio (MP3, Ogg/Opus, FLAC, AAC) with
 * UNSUPPORTED_FORMAT, a clip declared WAVE without a RIFF header with BAD_RESPONSE. Ask the speech
 * endpoint for [AudioEncoding.WAV].
 */
object ClipDecoder {
    /** [clip] as PCM. Throws [VoiceAudioException] when refused. */
    fun decode(clip: AudioClip): PcmAudio {
        val bytes = clip.bytes
        if (ContainerSniffer.isRiff(bytes)) return wav(bytes)
        val refusal = if (clip.encoding == AudioEncoding.WAV) VoiceErrorCode.BAD_RESPONSE else VoiceErrorCode.UNSUPPORTED_FORMAT
        throw VoiceAudioException(refusal)
    }

    private fun wav(bytes: ByteArray): PcmAudio = when (val parsed = WavCodec.parse(bytes)) {
        is WavParse.Decoded -> parsed.audio
        is WavParse.Refused -> throw VoiceAudioException(parsed.code)
    }
}

/**
 * Plays [clip] whole on this player, decoded by [ClipDecoder]: a clip it refuses throws
 * [VoiceAudioException] before the output is opened.
 */
suspend fun AudioPlayer.play(clip: AudioClip, onLevel: (AudioLevel) -> Unit) {
    val pcm = ClipDecoder.decode(clip)
    play(flowOf(PcmChunk(pcm.samples, pcm.format)), onLevel)
}

/** Recognises a RIFF container by its leading bytes. */
internal object ContainerSniffer {
    private val riff = "RIFF".encodeToByteArray()

    fun isRiff(bytes: ByteArray): Boolean = bytes.size >= riff.size && riff.indices.all { bytes[it] == riff[it] }
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
