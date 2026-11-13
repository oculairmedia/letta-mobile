package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.session.EndpointVoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.ui.ambient.AmbientVoice
import com.letta.mobile.ui.ambient.AmbientVoiceRole
import com.letta.mobile.ui.ambient.LocalAmbientVoice
import com.letta.mobile.ui.chat.surface.composer.voiceYouColor
import com.letta.mobile.ui.chat.voice.LocalVoiceSetup
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.newSpeechHttpEngine
import com.letta.mobile.ui.chat.voice.rememberMicrophoneAccess
import com.letta.mobile.ui.chat.voice.rememberVoiceAudio
import com.letta.mobile.ui.host.DismissWhenOwnerStops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * The chat page's voice mode over [sessions], or null where voice mode is not offered (no sessions,
 * no setup, the web). One controller per [sessions]: it shuts down, releasing the microphone and the
 * speaker, when the page leaves the composition (a window closing included) or [sessions] changes,
 * and stops when the page's owner stops (Android: the app goes to the background).
 */
@Composable
internal fun rememberVoiceMode(sessions: VoiceChatSessions?): VoiceModeHandle? {
    val setup = LocalVoiceSetup.current
    if (sessions == null || setup == null) return null
    val audio = rememberVoiceAudio() ?: return null
    val microphone = rememberMicrophoneAccess()
    val scope = rememberCoroutineScope()
    val handle = remember(sessions, setup, audio, microphone, scope) {
        val engines = EndpointVoiceEngineFactory(audio, ::newSpeechHttpEngine)
        val runtime = VoiceLoopRuntime(scope, Dispatchers.Default)
        VoiceModeHandle(VoiceModeController(sessions, setup.store, engines, microphone, runtime))
    }
    // The page's scope is still active here (it is forgotten after this effect); the teardown is
    // bounded by the loop's release budget and must outlive the page, so it is not its child.
    DisposableEffect(handle) { onDispose { scope.launch(NonCancellable) { handle.controller.shutdown() } } }
    DismissWhenOwnerStops { handle.controller.stop() }
    return handle
}

/**
 * Voice mode's drive for the page's ambient glows ([LocalAmbientVoice]): the person's colour and the
 * microphone level while listening and hearing, the agent's side and the playback level while
 * speaking; null otherwise, so the glows follow the run exactly as without voice mode (the thinking
 * animation while waiting for the reply included). It changes only when the role does; the glows
 * read the level at draw time.
 */
@Composable
internal fun rememberAmbientVoice(handle: VoiceModeHandle?): AmbientVoice? {
    if (handle == null) return null
    val state by handle.state.collectAsState()
    val you = voiceYouColor().toArgb()
    val role = when (state) {
        VoiceModeState.Listening, VoiceModeState.Hearing -> AmbientVoiceRole.You
        VoiceModeState.Speaking -> AmbientVoiceRole.Agent
        else -> return null
    }
    return remember(handle, role, you) {
        val level: () -> Float = if (role == AmbientVoiceRole.You) {
            { handle.microphoneLevel.value }
        } else {
            { handle.playbackLevel.value }
        }
        AmbientVoice(role, you, level)
    }
}
