package com.letta.mobile.data.voice.audio.android

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioCapture
import com.letta.mobile.data.voice.audio.AudioClip
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
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Which microphone path Android captures from. */
enum class AndroidCaptureMode {
    /** `VOICE_RECOGNITION`: unprocessed-ish audio for wake words and voice activity; no effects attached. */
    RECOGNITION,

    /**
     * `VOICE_COMMUNICATION`: the platform's call path, with acoustic echo cancellation and noise
     * suppression attached when the device offers them, so the app hears the person over its own
     * speech.
     */
    COMMUNICATION,
}

/** How an Android microphone is opened. */
data class AndroidCaptureOptions(
    /** The capture path. */
    val mode: AndroidCaptureMode = AndroidCaptureMode.RECOGNITION,
    /** The input device. */
    val device: AudioDeviceSelection = AudioDeviceSelection.SystemDefault,
)

/** The capture effects actually running on the open microphone. */
data class CaptureEffects(
    /** Acoustic echo cancellation is attached and enabled. */
    val echoCanceler: Boolean = false,
    /** Noise suppression is attached and enabled. */
    val noiseSuppressor: Boolean = false,
)

/**
 * An Android microphone frame source that also reports which capture effects are active while it
 * is open. [echoCancelled] is true on the COMMUNICATION path when the device offers acoustic echo
 * cancellation (the source attaches and enables it on every open).
 */
class AndroidFrameSource internal constructor(
    private val delegate: MeteredFrameSource,
    /** The effects running now; all false while closed or in RECOGNITION mode. */
    val effects: StateFlow<CaptureEffects>,
    override val echoCancelled: Boolean = false,
) : MeteredFrameSource by delegate

/**
 * Android microphone and speaker on `AudioRecord` and `AudioTrack`. It never requests a permission:
 * capture fails with PERMISSION_REQUIRED until the app has been granted `RECORD_AUDIO`. No
 * foreground service: capture works while the app is in the foreground.
 */
class AndroidAudio(private val context: Context) {
    private val manager: AudioManager = context.getSystemService(AudioManager::class.java)

    /**
     * A microphone frame source in exactly [format] (16-bit). Failures end the collection with a
     * [VoiceAudioException]: PERMISSION_REQUIRED, UNSUPPORTED_FORMAT, UNAVAILABLE (busy microphone,
     * missing device, a dead audio server, a line that stops delivering).
     */
    fun frameSource(
        format: AudioFrameFormat = AudioFrameFormat(),
        options: AndroidCaptureOptions = AndroidCaptureOptions(),
    ): AndroidFrameSource {
        val effects = MutableStateFlow(CaptureEffects())
        val source = LineFrameSource(AudioRecordInputDevice(context, options, effects), format)
        val echoCancelled = options.mode == AndroidCaptureMode.COMMUNICATION && echoCancellerAvailable()
        return AndroidFrameSource(source, effects.asStateFlow(), echoCancelled)
    }

    /** [AudioCapture] on the same microphone path as [frameSource]. */
    fun capture(options: AndroidCaptureOptions = AndroidCaptureOptions()): AudioCapture =
        FrameAudioCapture({ format -> frameSource(format, options) })

    /**
     * A speaker on a streaming `AudioTrack` with assistant usage. Each clip holds transient audio
     * focus that lets other apps duck, abandoned when the clip ends; losing focus stops the clip
     * with PLAYBACK_FAILED. Plays WAVE clips; refuses anything else
     * (build the endpoint synthesizer with `AudioEncoding.WAV`).
     */
    fun player(device: AudioDeviceSelection = AudioDeviceSelection.SystemDefault): AudioPlayer =
        FocusedPlayer(LinePcmPlayer(AudioTrackOutputDevice(context, device), PlayerOptions()), manager)

    /** Input devices, identified by their `AudioDeviceInfo` id. */
    fun inputs(): List<AudioDeviceOption> = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .map { info -> AudioDeviceOption(AudioDeviceId(info.id.toString()), info.productName.toString()) }

    /** Emits whenever an audio device is added or removed. */
    fun deviceChanges(): Flow<Unit> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(Unit)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(Unit)
            }
        }
        manager.registerAudioDeviceCallback(callback, null)
        awaitClose { manager.unregisterAudioDeviceCallback(callback) }
    }
}

/** Whether the device offers an acoustic echo canceller; a platform that throws counts as none. */
private fun echoCancellerAvailable(): Boolean = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)

/** Holds transient, duck-allowing audio focus around each clip; a focus loss stops the clip. */
internal class FocusedPlayer(private val delegate: AudioPlayer, private val manager: AudioManager) : AudioPlayer {
    override suspend fun play(clip: AudioClip, onLevel: (AudioLevel) -> Unit) = coroutineScope {
        val playing = launch(start = CoroutineStart.LAZY) { delegate.play(clip, onLevel) }
        val lost = AtomicBoolean(false)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(assistantAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change in stoppingChanges) {
                    lost.set(true)
                    playing.cancel()
                }
            }
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw VoiceAudioException(VoiceErrorCode.PLAYBACK_FAILED)
        }
        try {
            playing.start()
            playing.join()
        } finally {
            manager.abandonAudioFocusRequest(request)
        }
        if (lost.get()) throw VoiceAudioException(VoiceErrorCode.PLAYBACK_FAILED)
    }

    private companion object {
        val stoppingChanges = setOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    }
}
