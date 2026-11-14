package com.letta.mobile.ui.chat.voice

import androidx.compose.runtime.Composable
import com.letta.mobile.data.voice.audio.desktop.DesktopVoiceAudio
import com.letta.mobile.data.voice.endpoint.SpeechHttpEngines
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceAudio
import io.ktor.client.engine.HttpClientEngine

internal actual val voiceModeSupported: Boolean = true

internal actual fun newSpeechHttpEngine(): HttpClientEngine = SpeechHttpEngines.create()

/** The default Java Sound microphone and speaker. */
@Composable
internal actual fun rememberVoiceAudio(): VoiceAudio? = DesktopVoiceAudio

/** The desktop has no recording permission to ask for (the OS prompts a packaged app itself). */
@Composable
internal actual fun rememberMicrophoneAccess(): MicrophoneAccess = MicrophoneAccess.Granted
