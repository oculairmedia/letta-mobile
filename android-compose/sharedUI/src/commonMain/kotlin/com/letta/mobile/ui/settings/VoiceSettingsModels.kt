package com.letta.mobile.ui.settings

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.voice.host.client.HostVoiceState
import com.letta.mobile.data.voice.session.VoiceSetup
import org.jetbrains.compose.resources.StringResource

/** The last save's or test's outcome in the Voice section, resolved to words when drawn. */
@Immutable
internal class VoiceOutcome(val message: StringResource, val tone: SettingsTone)

/** Which rows the Voice section shows. */
internal enum class VoiceSettingsMode {
    /** This device's own speech server (the host shares none, or the person chose this device's own). */
    LOCAL,

    /** The host's shared speech server is in force: shown as the host reports it, editable by an admin. */
    SHARED,

    /** The host shares none yet, this device is an admin and has no server of its own: set one up for every device. */
    HOST_SETUP,
}

/** The rows for [local] against what is known of the host ([host]). */
internal fun voiceSettingsMode(local: VoiceSetup, host: HostVoiceState): VoiceSettingsMode = when {
    host.configured && !local.prefersDevice -> VoiceSettingsMode.SHARED
    canSetUpHost(local, host) -> VoiceSettingsMode.HOST_SETUP
    else -> VoiceSettingsMode.LOCAL
}

/** An admin device without a server of its own, on a host that serves the contract and has none yet. */
private fun canSetUpHost(local: VoiceSetup, host: HostVoiceState): Boolean {
    if (local.endpoint.baseUrl != null || host.admin != true) return false
    return host.supported && host.status?.configured == false
}
