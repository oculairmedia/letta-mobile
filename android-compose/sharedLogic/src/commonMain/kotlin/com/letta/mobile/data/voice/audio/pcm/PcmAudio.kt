package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.PcmFormat

/**
 * Decoded 16-bit PCM: interleaved [samples] in [format]. Its [toString] reports the size and
 * format only, so audio never reaches a log.
 */
class PcmAudio(
    /** The interleaved samples; never log or store them. */
    val samples: ShortArray,
    /** Rate and channel count. */
    val format: PcmFormat,
) {
    /** Samples per channel. */
    val frames: Int get() = samples.size / format.channels

    override fun toString(): String = "PcmAudio(samples=${samples.size}, format=$format)"
}

/**
 * An audio device, decoder or resampler failed with a fixed [code]. The message names the code
 * only; it never carries device names, paths or audio. Consumers attach the voice port themselves
 * (capture failures belong to the ear, playback failures to the mouth).
 */
class VoiceAudioException(
    /** Why it failed; PERMISSION_REQUIRED is never retried. */
    val code: VoiceErrorCode,
) : Exception("Voice audio failed: $code")
