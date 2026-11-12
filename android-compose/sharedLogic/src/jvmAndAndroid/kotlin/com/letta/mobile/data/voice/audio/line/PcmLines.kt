package com.letta.mobile.data.voice.audio.line

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException

/**
 * A blocking microphone line opened in an exact format: the minimal surface of `TargetDataLine`
 * and `AudioRecord` the capture logic needs, so that logic is tested without a device.
 */
internal interface PcmInputLine {
    /**
     * Reads up to `buffer.size` interleaved samples into [buffer] and returns how many arrived;
     * 0 means none yet. Blocks for at most about one buffer. Throws [VoiceAudioException] when the
     * device failed or disappeared.
     */
    fun read(buffer: ShortArray): Int

    /** Stops capture and unblocks a pending [read]; safe to call from another thread. */
    fun stop()

    /** Frees the device; called once, after the reading thread has exited. */
    fun release()
}

/** Opens a microphone line; throws [VoiceAudioException] (PERMISSION_REQUIRED, UNAVAILABLE, UNSUPPORTED_FORMAT). */
internal fun interface PcmInputDevice {
    /** A started line producing exactly [format]. */
    fun open(format: AudioFrameFormat): PcmInputLine
}

/** A blocking mono speaker line: the minimal surface of `SourceDataLine` and `AudioTrack`. */
internal interface PcmOutputLine {
    /** The format the line was actually opened in (its rate may differ from the one asked for). */
    val format: PcmFormat

    /**
     * Queues the first [count] of [samples], blocking until there is room, and returns how many
     * were queued; fewer (possibly 0) once [abort] was called.
     */
    fun write(samples: ShortArray, count: Int): Int

    /** Sample frames played so far. */
    fun playedFrames(): Long

    /** Everything has been written: play out what is queued (some platforms wait for a full buffer otherwise). */
    fun endOfStream() = Unit

    /** Stops playback at once and discards queued audio; unblocks [write]; safe from another thread. */
    fun abort()

    /** Frees the device; called once, after writing stopped. */
    fun release()
}

/** Opens a mono speaker line, at the asked rate when the device can, otherwise at its own. */
internal fun interface PcmOutputDevice {
    /** A started line; throws [VoiceAudioException] (UNAVAILABLE, PLAYBACK_FAILED). */
    fun open(format: PcmFormat): PcmOutputLine
}
