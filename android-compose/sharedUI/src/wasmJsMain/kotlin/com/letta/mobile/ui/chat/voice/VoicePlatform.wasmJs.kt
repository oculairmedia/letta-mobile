package com.letta.mobile.ui.chat.voice

import androidx.compose.runtime.Composable
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceAudio
import io.ktor.client.engine.HttpClientEngine

/** Voice mode is not offered on the web yet: no button, no Voice settings. */
internal actual val voiceModeSupported: Boolean = false

internal actual fun newSpeechHttpEngine(): HttpClientEngine = throw UnsupportedOperationException("Voice mode is not offered on the web")

@Composable
internal actual fun rememberVoiceAudio(): VoiceAudio? = null

@Composable
internal actual fun rememberMicrophoneAccess(): MicrophoneAccess = MicrophoneAccess.Granted
