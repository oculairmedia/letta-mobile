package com.letta.mobile.ui.chat.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoicePress
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.data.voice.session.echoCancelled

/**
 * Voice mode's stored setup and its reachability check, shared by the chat (to start) and the
 * Settings page (to edit). [check] contacts the server without sending audio or text.
 */
class VoiceSetupAccess(
    /** The stored setup. */
    val store: VoiceSetupStore,
    /** Whether this platform's voice microphone cancels its own echo (then speaking always interrupts). */
    val echoCancelled: Boolean = false,
    /** Whether a draft's server answers: null when it does, else why not. */
    val check: suspend (VoiceSetupDraft) -> VoiceErrorCode?,
)

/** The voice setup, where the platform offers voice mode; provided by the shell. */
val LocalVoiceSetup = staticCompositionLocalOf<VoiceSetupAccess?> { null }

/**
 * Draws the Voice settings over the chat; its argument is called when the person is done, with
 * whether they saved a complete setup. Provided by the shell, which owns the Settings sections.
 */
val LocalVoiceSetupPane = staticCompositionLocalOf<(@Composable (onDone: (saved: Boolean) -> Unit) -> Unit)?> { null }

/** The chat page's voice mode, or null where it is not offered; read by the chat bar. */
val LocalVoiceMode = staticCompositionLocalOf<VoiceModeHandle?> { null }

/**
 * The voice setup over [settings], the store the host keeps server tokens in, so the API key lands
 * in it (desktop registers the key as one of its encrypted keys). Null where voice mode is not offered.
 */
@Composable
fun rememberVoiceSetupAccess(settings: SecureSettingsStore): VoiceSetupAccess? {
    if (!voiceModeSupported) return null
    val audio = rememberVoiceAudio()
    return remember(settings, audio) {
        val store = VoiceSetupStore(settings = settings, secrets = settings)
        val echoCancelled = audio?.echoCancelled == true
        VoiceSetupAccess(store, echoCancelled) { draft -> store.check(draft, newSpeechHttpEngine()) }
    }
}

/** A page's voice mode: its controller and whether the Voice settings pane is open over the chat. */
@Stable
class VoiceModeHandle internal constructor(
    /** The controller the button and the status strip drive. */
    val controller: VoiceModeController,
) {
    /** Whether the Voice settings pane is showing (a press found the setup incomplete). */
    var setupOpen: Boolean by mutableStateOf(false)

    /**
     * The voice button: starts or stops voice mode, interrupts the reply while it is spoken, or opens
     * the Voice settings when setup is missing.
     */
    fun press() {
        if (controller.press() == VoicePress.OPEN_SETUP) setupOpen = true
    }
}

/** The Voice settings pane over the chat while [handle] asks for it; a complete save presses again. */
@Composable
fun VoiceSetupPaneHost(handle: VoiceModeHandle?) {
    if (handle == null || !handle.setupOpen) return
    val pane = LocalVoiceSetupPane.current ?: return
    pane { saved ->
        handle.setupOpen = false
        if (saved) handle.press()
    }
}
