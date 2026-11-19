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
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.catalog.SpeechCatalog
import com.letta.mobile.data.voice.endpoint.EndpointSpeechCatalog
import com.letta.mobile.data.voice.host.client.HostVoiceLink
import com.letta.mobile.data.voice.host.client.SharedVoiceSetup
import com.letta.mobile.data.voice.local.LocalHandoverReason
import com.letta.mobile.data.voice.local.LocalSpeechAvailability
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.data.voice.session.VoicePress
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.data.voice.session.migration.VoiceSetupMigration
import com.letta.mobile.data.voice.session.echoCancelled
import kotlinx.coroutines.flow.StateFlow

/**
 * Voice mode's stored setup and its reachability check, shared by the chat (to start) and the
 * Settings page (to edit), with the speech server the host shares with every device ([shared], null
 * where there is no host connection). [check] contacts this device's own server without sending
 * audio or text, and so does [catalog], which lists its models and voices (null where it cannot).
 */
class VoiceSetupAccess(
    /** The stored setup. */
    val store: VoiceSetupStore,
    /** Whether this platform's voice microphone cancels its own echo (then speaking always interrupts). */
    val echoCancelled: Boolean = false,
    /** Whether, and how, this device recognises speech itself (the Listening setting says so). */
    val localListening: LocalSpeechAvailability = LocalSpeechAvailability.UNAVAILABLE,
    /** The host's shared speech setup, reached over the host connection; null without one. */
    val shared: SharedVoiceSetup? = null,
    /** A draft's server's models and voices, asked directly with the key the draft would keep; null where not offered. */
    val catalog: (suspend (VoiceSetupDraft) -> SpeechCatalog)? = null,
    /** Whether a draft's server answers: null when it does, else why not. */
    val check: suspend (VoiceSetupDraft) -> VoiceErrorCode?,
) {
    /** Moving this device's own server to the host; null without a host connection. */
    val migration: VoiceSetupMigration? by lazy { shared?.let { VoiceSetupMigration(store, it) } }
}

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
 * in it (desktop registers the key as one of its encrypted keys), and the host's shared speech setup
 * over [hostLink] (the host connection; null where there is none). Null where voice mode is not offered.
 */
@Composable
fun rememberVoiceSetupAccess(settings: SecureSettingsStore, hostLink: HostVoiceLink? = null): VoiceSetupAccess? {
    if (!voiceModeSupported) return null
    val audio = rememberVoiceAudio()
    return remember(settings, audio, hostLink) {
        val store = VoiceSetupStore(settings = settings, secrets = settings)
        val echoCancelled = audio?.echoCancelled == true
        val local = audio?.localRecognizer()?.availability(null) ?: LocalSpeechAvailability.UNAVAILABLE
        val shared = hostLink?.let(::SharedVoiceSetup)
        val catalog: suspend (VoiceSetupDraft) -> SpeechCatalog = { draft ->
            EndpointSpeechCatalog.list(draft.baseUrl, store.keyAfter(draft), newSpeechHttpEngine())
        }
        VoiceSetupAccess(store, echoCancelled, local, shared, catalog) { draft -> store.check(draft, newSpeechHttpEngine()) }
    }
}

/**
 * A page's voice mode: its controller, what the chat bar shows of it (its state, the smoothed
 * microphone and playback levels, the words heard so far, the voice card's feed and a hand-over to
 * the server; the controller's own unless a test pins them) and whether the Voice settings pane is
 * open over the chat.
 */
@Stable
class VoiceModeHandle internal constructor(
    /** The controller the button and the status strip drive. */
    val controller: VoiceModeController,
    internal val state: StateFlow<VoiceModeState> = controller.state,
    internal val microphoneLevel: StateFlow<Float> = controller.microphoneLevel,
    internal val playbackLevel: StateFlow<Float> = controller.playbackLevel,
    internal val partialTranscript: StateFlow<VoiceText?> = controller.partialTranscript,
    /** What the voice card reads beyond the state: the reply's spoken pieces and the controls it offers. */
    internal val cardFeed: VoiceCardFeed = controllerVoiceCardFeed(controller),
) {
    internal val handover: StateFlow<LocalHandoverReason?> = controller.handover

    /** Whether the person folded the voice card down to its header line; kept across voice sessions. */
    internal var cardCollapsed: Boolean by mutableStateOf(false)

    /** Whether the person closed this session's hand-over notice; reset when the session ends. */
    internal var handoverClosed: Boolean by mutableStateOf(false)

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
