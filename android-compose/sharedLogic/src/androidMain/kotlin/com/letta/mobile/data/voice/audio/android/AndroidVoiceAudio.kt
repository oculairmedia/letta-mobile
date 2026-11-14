package com.letta.mobile.data.voice.audio.android

import android.content.Context
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.session.VoiceAudio
import kotlinx.coroutines.flow.Flow

/**
 * Voice mode's audio on Android ([AndroidAudio]): `AudioRecord` on the communication path, so echo
 * cancellation lets the person talk over the reply (the microphone reports `echoCancelled` when the
 * device offers the canceller), and `AudioTrack` with assistant usage. Capture
 * fails with PERMISSION_REQUIRED until `RECORD_AUDIO` is granted.
 */
class AndroidVoiceAudio(context: Context) : VoiceAudio {
    private val audio = AndroidAudio(context.applicationContext)

    override fun microphone(format: AudioFrameFormat): AudioFrameSource =
        audio.frameSource(format, AndroidCaptureOptions(mode = AndroidCaptureMode.COMMUNICATION))

    override fun deviceChanges(): Flow<Unit> = audio.deviceChanges()

    override fun speaker(): AudioPlayer = audio.player()
}
