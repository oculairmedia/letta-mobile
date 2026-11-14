package com.letta.mobile.ui.chat.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.letta.mobile.data.voice.audio.android.AndroidVoiceAudio
import com.letta.mobile.data.voice.endpoint.SpeechHttpEngines
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceAudio
import io.ktor.client.engine.HttpClientEngine

internal actual val voiceModeSupported: Boolean = true

internal actual fun newSpeechHttpEngine(): HttpClientEngine = SpeechHttpEngines.create()

/** `AudioRecord` and `AudioTrack` over the application context. */
@Composable
internal actual fun rememberVoiceAudio(): VoiceAudio? {
    val context = LocalContext.current
    return remember(context) { AndroidVoiceAudio(context) }
}

/** `RECORD_AUDIO`, read from the context and asked for through the Activity Result API. */
@Composable
internal actual fun rememberMicrophoneAccess(): MicrophoneAccess {
    val context = LocalContext.current
    val access = remember(context) { AndroidMicrophoneAccess(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), access::answer)
    SideEffect { access.launcher = launcher }
    return access
}

/** Asks for `RECORD_AUDIO` once per request; a launch that cannot run counts as a refusal. */
private class AndroidMicrophoneAccess(private val context: Context) : MicrophoneAccess {
    var launcher: ActivityResultLauncher<String>? = null
    private var pending: ((Boolean) -> Unit)? = null

    override fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun request(onResult: (Boolean) -> Unit) {
        val launch = launcher ?: return onResult(false)
        pending = onResult
        val started = runCatching { launch.launch(Manifest.permission.RECORD_AUDIO) }.isSuccess
        if (!started) answer(false)
    }

    fun answer(granted: Boolean) {
        val callback = pending ?: return
        pending = null
        callback(granted)
    }
}
