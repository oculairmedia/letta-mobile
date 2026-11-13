package com.letta.mobile.data.voice.audio.desktop

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.session.VoiceAudio
import kotlinx.coroutines.flow.Flow

/** Voice mode's audio on the desktop: the default Java Sound microphone and speaker ([DesktopAudio]). */
object DesktopVoiceAudio : VoiceAudio {
    override fun microphone(format: AudioFrameFormat): AudioFrameSource = DesktopAudio.frameSource(format)

    override fun deviceChanges(): Flow<Unit> = DesktopAudio.deviceChanges()

    override fun speaker(): AudioPlayer = DesktopAudio.player()
}
