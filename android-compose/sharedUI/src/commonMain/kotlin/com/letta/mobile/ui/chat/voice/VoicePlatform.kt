package com.letta.mobile.ui.chat.voice

import androidx.compose.runtime.Composable
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceAudio
import io.ktor.client.engine.HttpClientEngine

/** Whether this platform offers voice mode: Android and the desktop do, the web does not (yet). */
internal expect val voiceModeSupported: Boolean

/** A fresh HTTP engine for the speech server; only called where [voiceModeSupported]. */
internal expect fun newSpeechHttpEngine(): HttpClientEngine

/** The platform's microphone and speaker for voice mode, or null where voice mode is not offered. */
@Composable
internal expect fun rememberVoiceAudio(): VoiceAudio?

/**
 * The recording permission: on Android the `RECORD_AUDIO` runtime permission, asked for through the
 * Activity Result API on the first press; elsewhere always granted.
 */
@Composable
internal expect fun rememberMicrophoneAccess(): MicrophoneAccess
