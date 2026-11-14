package com.letta.mobile.data.voice.audio.device

import com.letta.mobile.data.voice.audio.AudioCapture
import com.letta.mobile.data.voice.audio.AudioChunk
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.PcmFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.jvm.JvmInline

/**
 * A platform audio device's stable identifier (a Java Sound mixer name on desktop, an
 * `AudioDeviceInfo` id on Android). Opaque to common code; never shown to the person as is.
 */
@JvmInline
value class AudioDeviceId(
    /** The platform's identifier. */
    val value: String,
)

/** Which device to open. */
sealed interface AudioDeviceSelection {
    /** Whatever the operating system routes to by default; follows the person's system choice. */
    data object SystemDefault : AudioDeviceSelection

    /** One device; opening fails with UNAVAILABLE when it is gone. */
    data class Specific(
        /** The device. */
        val id: AudioDeviceId,
    ) : AudioDeviceSelection
}

/** A device the platform offers, for a picker. */
data class AudioDeviceOption(
    /** Its identifier. */
    val id: AudioDeviceId,
    /** The platform's display name for it. */
    val label: String,
)

/**
 * A device-backed frame source that reports how many frames it dropped because the consumer fell
 * behind (its bounded buffer keeps the newest audio and drops the oldest). Dropped frames leave
 * gaps in the sample positions; positions never go backwards.
 */
interface MeteredFrameSource : AudioFrameSource {
    /** Frames dropped since this source was created, across every collection. */
    val droppedFrames: Long
}

/** Opens a frame source for an exact format; the platform's microphone behind a format choice. */
fun interface AudioFrameSourceFactory {
    /** A source producing exactly [format]; collecting it fails typed when the device cannot. */
    fun open(format: AudioFrameFormat): AudioFrameSource
}

/**
 * [AudioCapture] on top of a frame source: each frame becomes one little-endian byte chunk. Shared
 * by every platform so capture and frame paths open the device the same way.
 */
class FrameAudioCapture(
    private val sources: AudioFrameSourceFactory,
    private val frameFormat: AudioFrameFormat = AudioFrameFormat(),
) : AudioCapture {
    override fun capture(format: PcmFormat): Flow<AudioChunk> {
        val frames = AudioFrameFormat(format.sampleRateHz, format.channels, frameFormat.frameSamples)
        return sources.open(frames).frames().map { frame -> AudioChunk(frame.samples.toLittleEndian()) }
    }

    private fun ShortArray.toLittleEndian(): ByteArray {
        val bytes = ByteArray(size * 2)
        forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr BYTE_BITS).toByte()
        }
        return bytes
    }

    private companion object {
        const val BYTE_BITS = 8
    }
}
