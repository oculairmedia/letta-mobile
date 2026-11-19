package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.audio.pcm.WavParse
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceException
import com.letta.mobile.data.voice.host.HostVoiceWire

/**
 * Fits a clip to the transcription limits of the voice-endpoint contract: at most
 * [HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_BYTES] of WAVE and [HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_MS]
 * of audio per request. The samples are never logged or kept.
 */
internal object HostVoiceClips {
    private const val HEADER_BYTES = 44
    private const val BYTES_PER_SAMPLE = 2
    private const val MS_PER_SECOND = 1_000L

    /**
     * [clip] unchanged when it is within both limits, else its samples cut into consecutive WAVE
     * pieces that each are. A clip that is not readable 16-bit WAVE fails with
     * [HostVoiceErrorCode.INVALID_REQUEST] before anything is sent.
     */
    fun split(clip: AudioClip): List<AudioClip> {
        if (clip.encoding != AudioEncoding.WAV) throw HostVoiceException(HostVoiceErrorCode.INVALID_REQUEST)
        val audio = (WavCodec.parse(clip.bytes) as? WavParse.Decoded)?.audio
            ?: throw HostVoiceException(HostVoiceErrorCode.INVALID_REQUEST)
        val maxFrames = maxFramesPerPiece(audio)
        if (clip.bytes.size <= HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_BYTES && audio.frames <= maxFrames) return listOf(clip)
        val channels = audio.format.channels
        return (0 until audio.frames step maxFrames).map { start ->
            val end = minOf(start + maxFrames, audio.frames)
            val samples = audio.samples.copyOfRange(start * channels, end * channels)
            AudioClip(WavCodec.encode(PcmAudio(samples, audio.format)), AudioEncoding.WAV)
        }
    }

    /** The most sample frames one request may carry under both limits. */
    private fun maxFramesPerPiece(audio: PcmAudio): Int {
        val frameBytes = audio.format.channels * BYTES_PER_SAMPLE
        val byBytes = (HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_BYTES - HEADER_BYTES) / frameBytes
        val byTime = audio.format.sampleRateHz * HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_MS / MS_PER_SECOND
        return minOf(byBytes.toLong(), byTime).toInt().coerceAtLeast(1)
    }
}
