package com.letta.mobile.data.voice.audio

import com.letta.mobile.data.voice.AudioLevel
import kotlinx.coroutines.flow.Flow

/** An audio container or sample format, with the name OpenAI-compatible endpoints use for it. */
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

    /** Raw 16-bit little-endian PCM. */
    PCM("pcm", "audio/pcm"),

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

/** The platform's speaker, used by mouths that synthesise their own audio. */
interface AudioPlayer {
    /**
     * Plays [clip] to the end, reporting loudness through [onLevel]. Cancelling the call stops
     * playback and returns only after the output is released.
     */
    suspend fun play(clip: AudioClip, onLevel: (AudioLevel) -> Unit)
}
