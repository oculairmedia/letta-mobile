package com.letta.mobile.data.voice.audio.desktop

import com.letta.mobile.data.voice.audio.AudioCapture
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.device.AudioDeviceId
import com.letta.mobile.data.voice.audio.device.AudioDeviceOption
import com.letta.mobile.data.voice.audio.device.AudioDeviceSelection
import com.letta.mobile.data.voice.audio.device.FrameAudioCapture
import com.letta.mobile.data.voice.audio.device.MeteredFrameSource
import com.letta.mobile.data.voice.audio.line.LineFrameSource
import com.letta.mobile.data.voice.audio.line.LinePcmPlayer
import com.letta.mobile.data.voice.audio.line.PlayerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Line
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop (JVM) microphone and speaker on Java Sound. Nothing here asks for a permission: on macOS
 * the system's microphone prompt belongs to the packaged app, and a denied microphone delivers
 * silence rather than an error (unverified per platform).
 */
object DesktopAudio {
    /**
     * A microphone frame source on `TargetDataLine` in exactly [format] (16-bit signed
     * little-endian). A device that cannot capture that format fails the collection with
     * UNSUPPORTED_FORMAT; a missing device or a busy line with UNAVAILABLE; a line that stops
     * delivering (hot-unplug) with UNAVAILABLE after its stall timeout. Wrap it in a
     * [com.letta.mobile.data.voice.audio.device.RecoveringFrameSource] with [deviceChanges] to
     * reopen after unplugging.
     */
    fun frameSource(
        format: AudioFrameFormat = AudioFrameFormat(),
        device: AudioDeviceSelection = AudioDeviceSelection.SystemDefault,
    ): MeteredFrameSource = LineFrameSource(JavaSoundInputDevice(device), format)

    /** [AudioCapture] on the same microphone lines as [frameSource]. */
    fun capture(device: AudioDeviceSelection = AudioDeviceSelection.SystemDefault): AudioCapture =
        FrameAudioCapture({ format -> frameSource(format, device) })

    /**
     * A speaker on `SourceDataLine`, playing PCM as it arrives. Opens the line at the stream's
     * rate, or at 48 kHz with resampling when the device refuses it.
     */
    fun player(device: AudioDeviceSelection = AudioDeviceSelection.SystemDefault): AudioPlayer =
        LinePcmPlayer(JavaSoundOutputDevice(device), PlayerOptions())

    /** Mixers that can capture. */
    fun inputs(): List<AudioDeviceOption> = devicesWith(TargetDataLine::class.java)

    /** Mixers that can play. */
    fun outputs(): List<AudioDeviceOption> = devicesWith(SourceDataLine::class.java)

    /**
     * Emits when the set of mixers changes, polled every [interval]. Some JDKs list mixers only
     * once per process, so a hot-plugged device may never appear here (unverified per OS).
     */
    fun deviceChanges(interval: Duration = 2.seconds): Flow<Unit> = flow {
        while (true) {
            emit(inputs() + outputs())
            delay(interval)
        }
    }.distinctUntilChanged().drop(1).map { }.flowOn(Dispatchers.IO)

    private fun devicesWith(kind: Class<out Line>): List<AudioDeviceOption> = AudioSystem.getMixerInfo()
        .filter { info -> AudioSystem.getMixer(info).isLineSupported(Line.Info(kind)) }
        .map { info -> AudioDeviceOption(AudioDeviceId(info.name), info.name) }
}
