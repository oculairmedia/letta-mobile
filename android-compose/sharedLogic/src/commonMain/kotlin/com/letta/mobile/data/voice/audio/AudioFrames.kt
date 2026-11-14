package com.letta.mobile.data.voice.audio

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Fixed-size 16-bit PCM frames: what a frame source produces and an engine requires. */
data class AudioFrameFormat(
    /** Samples per second. */
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    /** Interleaved channels. */
    val channels: Int = 1,
    /** Samples per channel in one frame. */
    val frameSamples: Int = DEFAULT_FRAME_SAMPLES,
) {
    /** Interleaved samples in one frame. */
    val samplesPerFrame: Int get() = frameSamples * channels

    /** The audio time at [position]. */
    fun timeAt(position: SamplePosition): Duration = (position.value.toDouble() / sampleRateHz).seconds

    private companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_FRAME_SAMPLES = 512
    }
}

/** A position in a capture, counted in samples per channel from the start of the capture. */
@kotlin.jvm.JvmInline
value class SamplePosition(
    /** Samples since the capture started. */
    val value: Long,
)

/**
 * One frame of interleaved 16-bit PCM. The array may be reused by the source after the frame has
 * been processed; consumers must not keep it. Its [toString] reports the size only.
 */
class AudioFrame(
    /** The samples; never log or store them. */
    val samples: ShortArray,
    /** Where the frame starts in the capture. */
    val start: SamplePosition,
) {
    override fun toString(): String = "AudioFrame(samples=${samples.size}, start=${start.value})"
}

/**
 * The platform's microphone as fixed-size PCM frames, for consumers that process audio themselves
 * (wake-word engines). [AudioCapture] yields encoded byte runs without frame size or position;
 * this yields frames an engine can run on directly.
 *
 * Collecting [frames] opens the device; cancelling closes it, and the collection does not complete
 * until the device is released. A source never resamples: it produces [format] or nothing.
 */
interface AudioFrameSource {
    /** The format this source produces. */
    val format: AudioFrameFormat

    /**
     * Whether the platform cancels the app's own playback out of this microphone path (acoustic
     * echo cancellation), so the person can be heard over the app's speech. False unless the
     * platform says so: a plain microphone also hears the speakers.
     */
    val echoCancelled: Boolean get() = false

    /** Frames until cancelled. */
    fun frames(): Flow<AudioFrame>
}
