package com.letta.mobile.data.voice.audio

import com.letta.mobile.data.voice.AudioLevel
import kotlinx.coroutines.flow.Flow

/**
 * An audio container, with the name OpenAI-compatible endpoints use for it. Headerless PCM is not
 * offered: a clip carries no sample rate or channel count, so a player could not play it correctly.
 */
enum class AudioEncoding(
    /** The `response_format` name and file extension. */
    val wireName: String,
    /** The MIME type. */
    val mimeType: String,
) {
    /** MPEG layer 3. */
    MP3("mp3", "audio/mpeg"),

    /** RIFF WAVE. */
    WAV("wav", "audio/wav"),

    /** Opus in Ogg. */
    OPUS("opus", "audio/ogg"),

    /** AAC. */
    AAC("aac", "audio/aac"),

    /** FLAC. */
    FLAC("flac", "audio/flac"),
}

/** Raw PCM capture parameters. */
data class PcmFormat(
    /** Samples per second. */
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    /** Interleaved channels. */
    val channels: Int = 1,
) {
    private companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
    }
}

/** A run of captured audio bytes. Its [toString] reports the size only. */
class AudioChunk(
    /** The bytes; never log them. */
    val bytes: ByteArray,
) {
    override fun toString(): String = "AudioChunk(bytes=${bytes.size})"
}

/** A complete piece of encoded audio. Its [toString] reports the size and format only. */
class AudioClip(
    /** The encoded bytes; never log them. */
    val bytes: ByteArray,
    /** How they are encoded. */
    val encoding: AudioEncoding,
) {
    override fun toString(): String = "AudioClip(bytes=${bytes.size}, encoding=$encoding)"
}

/**
 * The platform's microphone, used by ears that capture their own audio. Collecting [capture] opens
 * the device; cancelling closes it and must not return until the device is released.
 */
interface AudioCapture {
    /** Captured audio in [format] until cancelled. */
    fun capture(format: PcmFormat): Flow<AudioChunk>
}

/**
 * A run of streamed 16-bit PCM: whole interleaved sample frames in [format]. Its [toString] reports
 * the size and format only.
 */
class PcmChunk(
    /** The interleaved samples; never log or store them. */
    val samples: ShortArray,
    /** Rate and channel count. */
    val format: PcmFormat,
) {
    override fun toString(): String = "PcmChunk(samples=${samples.size}, format=$format)"
}

/** The platform's speaker, used by mouths that synthesise their own audio. */
interface AudioPlayer {
    /**
     * Plays [chunks] as they arrive, reporting loudness through [onLevel], and returns only once
     * everything collected has been played out, not when the last chunk arrived. A wait between
     * chunks is an underrun, never the end. Every chunk has the first chunk's format; an empty flow
     * plays nothing. Cancelling the call stops playback at once, stops collecting [chunks] and
     * returns only after the output is released.
     */
    suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit)
}
