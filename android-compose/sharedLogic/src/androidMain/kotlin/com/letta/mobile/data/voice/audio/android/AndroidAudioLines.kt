package com.letta.mobile.data.voice.audio.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.device.AudioDeviceSelection
import com.letta.mobile.data.voice.audio.line.PcmInputDevice
import com.letta.mobile.data.voice.audio.line.PcmInputLine
import com.letta.mobile.data.voice.audio.line.PcmOutputDevice
import com.letta.mobile.data.voice.audio.line.PcmOutputLine
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import kotlinx.coroutines.flow.MutableStateFlow

/** The attributes every assistant playback uses (speech, assistant usage). */
internal val assistantAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

private const val BYTES_PER_SAMPLE = 2
private const val BUFFER_MULTIPLIER = 4

/** The `MediaRecorder.AudioSource` for [mode]. */
internal fun audioSourceFor(mode: AndroidCaptureMode): Int = when (mode) {
    AndroidCaptureMode.RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    AndroidCaptureMode.COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
}

private fun pcm16(sampleRateHz: Int, channelMask: Int): AudioFormat =
    AudioFormat.Builder().setSampleRate(sampleRateHz).setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()

private fun channelMaskIn(channels: Int): Int = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

private fun AudioManager.deviceFor(selection: AudioDeviceSelection, flags: Int): AudioDeviceInfo? = when (selection) {
    AudioDeviceSelection.SystemDefault -> null
    is AudioDeviceSelection.Specific -> getDevices(flags).firstOrNull { it.id.toString() == selection.id.value }
        ?: throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
}

/**
 * Microphone lines on `AudioRecord`. Fails with PERMISSION_REQUIRED (and opens nothing) when
 * `RECORD_AUDIO` is not granted; the app requests the permission, never this class.
 */
internal class AudioRecordInputDevice(
    private val context: Context,
    private val options: AndroidCaptureOptions,
    private val effects: MutableStateFlow<CaptureEffects>,
) : PcmInputDevice {
    @SuppressLint("MissingPermission")
    override fun open(format: AudioFrameFormat): PcmInputLine {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw VoiceAudioException(VoiceErrorCode.PERMISSION_REQUIRED)
        val mask = channelMaskIn(format.channels)
        val minimum = AudioRecord.getMinBufferSize(format.sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw VoiceAudioException(VoiceErrorCode.UNSUPPORTED_FORMAT)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(audioSourceFor(options.mode))
                .setAudioFormat(pcm16(format.sampleRateHz, mask))
                .setBufferSizeInBytes(maxOf(minimum, format.samplesPerFrame * BYTES_PER_SAMPLE) * BUFFER_MULTIPLIER)
                .build()
        }.getOrElse { throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE) }
        return start(record)
    }

    private fun start(record: AudioRecord): PcmInputLine {
        val manager = context.getSystemService(AudioManager::class.java)
        val ready = record.state == AudioRecord.STATE_INITIALIZED
        if (ready) runCatching { manager.deviceFor(options.device, AudioManager.GET_DEVICES_INPUTS)?.let(record::setPreferredDevice) }
        if (ready) record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
        }
        val attached = AttachedEffects.attach(record.audioSessionId, options.mode)
        effects.value = attached.report
        return AudioRecordInputLine(record, attached) { effects.value = CaptureEffects() }
    }
}

/** Echo cancellation and noise suppression attached to one recording session. */
internal class AttachedEffects(private val active: List<AudioEffect>, val report: CaptureEffects) {
    fun release() = active.forEach { runCatching { it.release() } }

    companion object {
        /** Attaches both effects in COMMUNICATION mode when the device offers them; nothing otherwise. */
        fun attach(session: Int, mode: AndroidCaptureMode): AttachedEffects {
            if (mode != AndroidCaptureMode.COMMUNICATION) return AttachedEffects(emptyList(), CaptureEffects())
            val echo = createIf(AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(session) }
            val noise = createIf(NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(session) }
            val enabled = listOfNotNull(echo, noise).filter(::enable)
            (listOfNotNull(echo, noise) - enabled.toSet()).forEach { runCatching { it.release() } }
            return AttachedEffects(enabled, CaptureEffects(echoCanceler = echo in enabled, noiseSuppressor = noise in enabled))
        }

        private fun createIf(available: Boolean, create: () -> AudioEffect?): AudioEffect? =
            if (available) runCatching(create).getOrNull() else null

        private fun enable(effect: AudioEffect): Boolean =
            runCatching { effect.setEnabled(true) == AudioEffect.SUCCESS }.getOrDefault(false)
    }
}

private class AudioRecordInputLine(
    private val record: AudioRecord,
    private val effects: AttachedEffects,
    private val onReleased: () -> Unit,
) : PcmInputLine {
    override fun read(buffer: ShortArray): Int {
        val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        if (read == AudioRecord.ERROR_DEAD_OBJECT) throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
        if (read < 0) throw VoiceAudioException(VoiceErrorCode.ENGINE_FAILED)
        return read
    }

    override fun stop() {
        runCatching { record.stop() }
    }

    override fun release() {
        effects.release()
        record.release()
        onReleased()
    }
}

/** Speaker lines on `AudioTrack` in stream mode with assistant usage; any rate the platform accepts. */
internal class AudioTrackOutputDevice(private val context: Context, private val device: AudioDeviceSelection) : PcmOutputDevice {
    override fun open(format: PcmFormat): PcmOutputLine {
        val mask = AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(format.sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw VoiceAudioException(VoiceErrorCode.UNSUPPORTED_FORMAT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(assistantAttributes)
                .setAudioFormat(pcm16(format.sampleRateHz, mask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minimum * 2)
                .build()
        }.getOrElse { throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE) }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
        }
        val manager = context.getSystemService(AudioManager::class.java)
        runCatching { manager.deviceFor(device, AudioManager.GET_DEVICES_OUTPUTS)?.let(track::setPreferredDevice) }
        track.play()
        return AudioTrackOutputLine(track, PcmFormat(format.sampleRateHz))
    }
}

private class AudioTrackOutputLine(private val track: AudioTrack, override val format: PcmFormat) : PcmOutputLine {
    override fun write(samples: ShortArray, count: Int): Int {
        val written = track.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
        if (written == AudioTrack.ERROR_DEAD_OBJECT) throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
        return written
    }

    override fun playedFrames(): Long = track.playbackHeadPosition.toLong() and UNSIGNED_INT_MASK

    /**
     * In stream mode a clip shorter than the buffer may not start until the buffer fills, and stop()
     * resets the head position; so the tail is pushed out with one buffer of silence instead.
     */
    override fun endOfStream() {
        val silence = ShortArray(track.bufferSizeInFrames)
        track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
    }

    override fun abort() {
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        }
    }

    override fun release() {
        track.release()
    }

    private companion object {
        const val UNSIGNED_INT_MASK = 0xFFFFFFFFL
    }
}
