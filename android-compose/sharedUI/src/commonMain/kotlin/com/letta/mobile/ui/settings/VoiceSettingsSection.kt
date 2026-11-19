package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.host.client.HostVoiceState
import com.letta.mobile.data.voice.session.VoiceEndpointRules
import com.letta.mobile.data.voice.session.VoiceListening
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_settings_consent
import com.letta.mobile.sharedui.resources.voice_settings_consent_help
import com.letta.mobile.sharedui.resources.voice_settings_interrupt
import com.letta.mobile.sharedui.resources.voice_settings_interrupt_builtin
import com.letta.mobile.sharedui.resources.voice_settings_interrupt_help
import com.letta.mobile.sharedui.resources.voice_settings_key
import com.letta.mobile.sharedui.resources.voice_settings_key_remove
import com.letta.mobile.sharedui.resources.voice_settings_key_saved
import com.letta.mobile.sharedui.resources.voice_settings_listening
import com.letta.mobile.sharedui.resources.voice_settings_listening_automatic
import com.letta.mobile.sharedui.resources.voice_settings_listening_automatic_help
import com.letta.mobile.sharedui.resources.voice_settings_listening_local
import com.letta.mobile.sharedui.resources.voice_settings_listening_local_help
import com.letta.mobile.sharedui.resources.voice_settings_listening_local_unavailable
import com.letta.mobile.sharedui.resources.voice_settings_listening_server
import com.letta.mobile.sharedui.resources.voice_settings_listening_server_help
import com.letta.mobile.sharedui.resources.voice_settings_reachable
import com.letta.mobile.sharedui.resources.voice_settings_save
import com.letta.mobile.sharedui.resources.voice_settings_saved
import com.letta.mobile.sharedui.resources.voice_settings_supporting
import com.letta.mobile.sharedui.resources.voice_settings_test
import com.letta.mobile.sharedui.resources.voice_settings_testing
import com.letta.mobile.sharedui.resources.voice_settings_title
import com.letta.mobile.sharedui.resources.voice_settings_url
import com.letta.mobile.sharedui.resources.voice_settings_url_help
import com.letta.mobile.sharedui.resources.voice_settings_url_placeholder
import com.letta.mobile.sharedui.resources.voice_setup_title
import com.letta.mobile.ui.chat.voice.VoiceSetupAccess
import com.letta.mobile.ui.chat.voice.voiceErrorText
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The last save's or test's outcome, resolved to words when drawn. */
@Immutable
internal class VoiceOutcome(val message: StringResource, val tone: SettingsTone)

private fun outcomeOf(code: VoiceErrorCode?, success: StringResource): VoiceOutcome =
    code?.let { VoiceOutcome(voiceErrorText(it), SettingsTone.Error) } ?: VoiceOutcome(success, SettingsTone.Positive)

/** What the Voice section is editing: kept in memory only (a typed key never enters saved UI state). */
@Stable
private class VoiceForm(private val access: VoiceSetupAccess, url: String, consent: Boolean, private val scope: CoroutineScope) {
    var url: String by mutableStateOf(url)
    var key: String by mutableStateOf("")
    var consent: Boolean by mutableStateOf(consent)
    var outcome: VoiceOutcome? by mutableStateOf(null)
    var testing: Boolean by mutableStateOf(false)

    val draft: VoiceSetupDraft get() = VoiceSetupDraft(url, key.takeIf { it.isNotEmpty() }, consent)

    /** The address problem under the endpoint client's rules, counting the key that would be kept. */
    val problem: VoiceErrorCode? get() = VoiceEndpointRules.problem(url, withKey = access.store.keyAfter(draft) != null)

    /** Applies an edit and clears the last outcome. */
    fun edit(change: VoiceForm.() -> Unit) {
        change()
        outcome = null
    }

    fun save(onSaved: ((ready: Boolean) -> Unit)?) {
        val error = access.store.save(draft)
        outcome = outcomeOf(error, Res.string.voice_settings_saved)
        if (error != null) return
        key = ""
        onSaved?.invoke(access.store.setup.value.gap == null)
    }

    fun test() {
        testing = true
        scope.launch {
            outcome = outcomeOf(access.check(draft), Res.string.voice_settings_reachable)
            testing = false
        }
    }

    fun removeKey() {
        key = ""
        outcome = outcomeOf(access.store.save(VoiceSetupDraft(url, apiKey = "", consent = consent)), Res.string.voice_settings_saved)
    }
}

/**
 * The Voice section. Where the connected host shares a speech server, it shows that server as the
 * host reports it (address, whether a key is kept, models, reachability), an admin device's editor
 * for it and this device's own choices (its one-time audio acknowledgement, using its own server
 * instead); nothing asks for a server here then. An admin device whose host has none sets one up for
 * every device. Otherwise it is this device's own server: its URL (validated by the endpoint
 * client's own rules, its problem shown under the field), an optional API key where the platform
 * keeps keys in its secure store (typed keys live only in memory until saved, never in saved UI
 * state), the plain-language consent to send audio and reply text to that server, Save, a Test that
 * only checks the server answers, and the offer to share it with every device. Which ear listens
 * and whether speaking interrupts are this device's settings either way. [onSaved] hears each
 * successful save, with whether voice mode can now start.
 */
@Composable
fun VoiceSettingsSection(access: VoiceSetupAccess, modifier: Modifier = Modifier, onSaved: ((ready: Boolean) -> Unit)? = null) {
    val stored by access.store.setup.collectAsState()
    val shared = access.shared
    val noHost = remember { MutableStateFlow(HostVoiceState()) }
    val host by (shared?.state ?: noHost).collectAsState()
    // Reads the shared setup and learns whether this device may change it (a check that sends no audio or text).
    LaunchedEffect(shared) { shared?.learnAdmin() }
    val scope = rememberCoroutineScope()
    val form = remember(access) { VoiceForm(access, stored.endpoint.baseUrl?.value.orEmpty(), stored.consented, scope) }
    val keySaved = stored.hasApiKey && access.store.keepsApiKey
    val header = SettingsHeader(
        VoiceSettingsKeys.SECTION,
        stringResource(Res.string.voice_settings_title),
        stringResource(Res.string.voice_settings_supporting),
    )
    SettingsSection(header, modifier) {
        val mode = voiceSettingsMode(stored, host)
        when (mode) {
            VoiceSettingsMode.SHARED -> SharedVoiceRows(access, host, stored, onSaved)
            VoiceSettingsMode.HOST_SETUP -> shared?.let { HostVoiceSetupRows(it, onSaved) }
            VoiceSettingsMode.LOCAL -> {
                VoiceAddressRows(form, keepsKey = access.store.keepsApiKey, keySaved = keySaved)
                VoiceConsentRow(form)
            }
        }
        VoiceListeningRow(access, stored.listening)
        VoiceInterruptRow(access, stored.interruptBySpeaking)
        if (mode == VoiceSettingsMode.LOCAL) {
            VoiceActionRow(form, keySaved, onSaved)
            form.outcome?.let { NoteRow(SettingsRow.Note(VoiceSettingsKeys.STATUS, SettingsStatus(stringResource(it.message), it.tone))) }
            VoiceShareRows(access, host, stored)
        }
    }
}

@Composable
private fun SettingsScope.VoiceAddressRows(form: VoiceForm, keepsKey: Boolean, keySaved: Boolean) {
    val problemText = form.problem?.let { stringResource(voiceErrorText(it)) }
    val urlRow = SettingsRow.Text(
        key = VoiceSettingsKeys.URL,
        title = stringResource(Res.string.voice_settings_url),
        placeholder = stringResource(Res.string.voice_settings_url_placeholder),
        supporting = stringResource(Res.string.voice_settings_url_help),
        problem = { problemText },
    )
    TextFieldRow(urlRow, form.url) { form.edit { url = it } }
    if (!keepsKey) return
    val keyRow = SettingsRow.Text(
        key = VoiceSettingsKeys.KEY,
        title = stringResource(Res.string.voice_settings_key),
        supporting = if (keySaved) stringResource(Res.string.voice_settings_key_saved) else null,
        secret = true,
    )
    TextFieldRow(keyRow, form.key) { form.edit { key = it } }
}

@Composable
private fun SettingsScope.VoiceConsentRow(form: VoiceForm) {
    val row = SettingsRow.Toggle(
        key = VoiceSettingsKeys.CONSENT,
        title = stringResource(Res.string.voice_settings_consent),
        checked = form.consent,
        supporting = stringResource(Res.string.voice_settings_consent_help),
    ) { checked -> form.edit { consent = checked } }
    ToggleRow(row)
}

/**
 * Which ear voice mode listens with, the same choice on every client: Automatic (this device's own
 * recognition where it has one, else the speech server), this device only (said to be unavailable
 * where the device has none), or the speech server. Stored at once, from the next start.
 */
@Composable
private fun SettingsScope.VoiceListeningRow(access: VoiceSetupAccess, listening: VoiceListening) {
    val localHelp = if (access.localListening.available) {
        Res.string.voice_settings_listening_local_help
    } else {
        Res.string.voice_settings_listening_local_unavailable
    }
    val options = listOf(
        listeningOption(
            VoiceListening.AUTOMATIC,
            Res.string.voice_settings_listening_automatic,
            Res.string.voice_settings_listening_automatic_help,
        ),
        listeningOption(VoiceListening.LOCAL, Res.string.voice_settings_listening_local, localHelp),
        listeningOption(VoiceListening.SERVER, Res.string.voice_settings_listening_server, Res.string.voice_settings_listening_server_help),
    )
    val row = SettingsRow.Choice(
        key = VoiceSettingsKeys.LISTENING,
        title = stringResource(Res.string.voice_settings_listening),
        options = options,
        selected = listening.name,
        // The chosen option's own description: a pointer's segmented control shows only this line.
        supporting = options.first { it.key == listening.name }.description,
        onSelect = { picked -> access.store.setListening(VoiceListening.valueOf(picked)) },
    )
    ChoiceRow(row)
}

@Composable
private fun listeningOption(value: VoiceListening, label: StringResource, help: StringResource): SettingsOption =
    SettingsOption(value.name, stringResource(label), stringResource(help))

/**
 * Whether speaking over a reply interrupts it. Where the microphone cancels its own echo it always
 * does, and this is only a note; elsewhere it is off by default (the replies would interrupt
 * themselves through the speakers) and the person can turn it on when they use headphones. It is
 * stored at once, apart from Save, and applies from the next start of voice mode.
 */
@Composable
private fun SettingsScope.VoiceInterruptRow(access: VoiceSetupAccess, enabled: Boolean) {
    if (access.echoCancelled) {
        val note = SettingsStatus(stringResource(Res.string.voice_settings_interrupt_builtin))
        NoteRow(SettingsRow.Note(VoiceSettingsKeys.INTERRUPT, note))
        return
    }
    val row = SettingsRow.Toggle(
        key = VoiceSettingsKeys.INTERRUPT,
        title = stringResource(Res.string.voice_settings_interrupt),
        checked = enabled,
        supporting = stringResource(Res.string.voice_settings_interrupt_help),
    ) { checked -> access.store.setInterruptBySpeaking(checked) }
    ToggleRow(row)
}

@Composable
private fun SettingsScope.VoiceActionRow(form: VoiceForm, keySaved: Boolean, onSaved: ((ready: Boolean) -> Unit)?) {
    val usable = form.problem == null
    val testLabel = stringResource(if (form.testing) Res.string.voice_settings_testing else Res.string.voice_settings_test)
    val saveLabel = stringResource(Res.string.voice_settings_save)
    val save = ActionButton(VoiceSettingsKeys.SAVE, saveLabel, { form.save(onSaved) }, primary = true, enabled = usable)
    val test = ActionButton(VoiceSettingsKeys.TEST, testLabel, form::test, enabled = usable && !form.testing)
    val removeLabel = stringResource(Res.string.voice_settings_key_remove)
    val remove = if (keySaved) ActionButton("voice-remove-key", removeLabel, form::removeKey) else null
    ActionRow("voice-actions", listOfNotNull(save, test, remove))
}

/**
 * The Voice section over the chat, where a press found the setup incomplete: a dialog on a
 * pointer, a sheet on touch. [onDone] hears whether a save left voice mode ready to start (false
 * when the person dismissed it).
 */
@Composable
fun VoiceSetupPane(access: VoiceSetupAccess, onDone: (ready: Boolean) -> Unit) {
    LettaPane(
        kind = LettaPaneKind.Modal,
        onDismiss = { onDone(false) },
        title = stringResource(Res.string.voice_setup_title),
        testTag = VoiceSettingsKeys.PANE,
    ) {
        VoiceSettingsSection(access, onSaved = { ready -> if (ready) onDone(true) })
    }
}
