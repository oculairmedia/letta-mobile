package com.letta.mobile.data.media

/** Why an engine could not play its source. */
enum class MediaEngineFailure {
    /** The engine cannot decode this format or container; offer to open it elsewhere. */
    Unsupported,

    /** The source could not be read: offline, a server error, a corrupt file. */
    Failed,
}

/** One reading of an engine. Positions and durations are milliseconds; null duration is not known yet. */
data class MediaEngineReading(
    /** Whether a source is open and decodable. */
    val hasMedia: Boolean = false,
    /** Whether output is running now (not merely requested). */
    val isPlaying: Boolean = false,
    /** Whether the engine is reading or buffering. */
    val isLoading: Boolean = false,
    /** Where output is now. */
    val positionMs: Long = 0L,
    /** The source's length once the engine knows it. */
    val durationMs: Long? = null,
    /** Set once the engine gave up on the source. */
    val failure: MediaEngineFailure? = null,
    /** Set when the engine reached the end; cleared by the next open, play or seek. */
    val atEnd: Boolean = false,
    /** Decoded frame width in pixels once known; null for audio. */
    val videoWidth: Int? = null,
    /** Decoded frame height in pixels once known; null for audio. */
    val videoHeight: Int? = null,
)

/**
 * A third-party playback engine as [EngineMediaPlayer] drives it. An engine binding only translates
 * commands and readings; the port's state machine (queued play, paused versus ready, the end, release)
 * lives once in [EngineMediaPlayer], which is what passes the shared contract. Engines may run their
 * work on other threads: a reading is polled, never pushed.
 */
interface MediaEngine {
    /** Opens [source] without starting output. Never logs the source. */
    fun open(source: MediaSource)

    /** Starts or resumes output. */
    fun play()

    /** Pauses output, keeping the position. */
    fun pause()

    /** Moves to [positionMs], already clamped to the duration. */
    fun seekTo(positionMs: Long)

    /** The effective output level, 0..1 (zero while muted). */
    fun setOutputVolume(volume: Float)

    /** The engine's state now. */
    fun read(): MediaEngineReading

    /** Frees the engine and anything written for its source; final. */
    fun dispose()
}
