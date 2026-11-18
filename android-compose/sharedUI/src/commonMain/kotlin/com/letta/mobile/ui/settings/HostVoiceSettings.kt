package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.letta.mobile.data.rpcwire.SecretWriteValue
import com.letta.mobile.data.settings.SettingsConfirmText
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceConnectionCandidate
import com.letta.mobile.data.voice.host.HostVoiceEndpointChoice
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceGrants
import com.letta.mobile.data.voice.host.HostVoiceKeyChange
import com.letta.mobile.data.voice.host.HostVoiceReachabilityState
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus
import com.letta.mobile.data.voice.host.client.HostVoiceState
import com.letta.mobile.data.voice.host.client.SharedVoiceSetup
import com.letta.mobile.data.voice.session.VoiceEndpointRules
import com.letta.mobile.data.voice.session.VoiceSetup
import com.letta.mobile.data.voice.session.migration.VoiceShareOffer
import com.letta.mobile.data.voice.session.migration.shareOfferFor
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_host_ack
import com.letta.mobile.sharedui.resources.voice_host_ack_help
import com.letta.mobile.sharedui.resources.voice_host_clear
import com.letta.mobile.sharedui.resources.voice_host_clear_confirm
import com.letta.mobile.sharedui.resources.voice_host_clear_confirm_message
import com.letta.mobile.sharedui.resources.voice_host_clear_confirm_title
import com.letta.mobile.sharedui.resources.voice_host_conflict
import com.letta.mobile.sharedui.resources.voice_host_device_only
import com.letta.mobile.sharedui.resources.voice_host_device_only_help
import com.letta.mobile.sharedui.resources.voice_host_grant_audio
import com.letta.mobile.sharedui.resources.voice_host_grant_help
import com.letta.mobile.sharedui.resources.voice_host_grant_text
import com.letta.mobile.sharedui.resources.voice_host_incomplete
import com.letta.mobile.sharedui.resources.voice_host_key
import com.letta.mobile.sharedui.resources.voice_host_key_field
import com.letta.mobile.sharedui.resources.voice_host_key_field_help
import com.letta.mobile.sharedui.resources.voice_host_key_none
import com.letta.mobile.sharedui.resources.voice_host_key_stored
import com.letta.mobile.sharedui.resources.voice_host_listening
import com.letta.mobile.sharedui.resources.voice_host_not_set
import com.letta.mobile.sharedui.resources.voice_host_note
import com.letta.mobile.sharedui.resources.voice_host_reachability
import com.letta.mobile.sharedui.resources.voice_host_reachable
import com.letta.mobile.sharedui.resources.voice_host_read_only
import com.letta.mobile.sharedui.resources.voice_host_save
import com.letta.mobile.sharedui.resources.voice_host_server
import com.letta.mobile.sharedui.resources.voice_host_setup_note
import com.letta.mobile.sharedui.resources.voice_host_speaking
import com.letta.mobile.sharedui.resources.voice_host_speech_model
import com.letta.mobile.sharedui.resources.voice_host_transcription_model
import com.letta.mobile.sharedui.resources.voice_host_unchecked
import com.letta.mobile.sharedui.resources.voice_host_voice
import com.letta.mobile.sharedui.resources.voice_settings_reachable
import com.letta.mobile.sharedui.resources.voice_settings_saved
import com.letta.mobile.sharedui.resources.voice_settings_test
import com.letta.mobile.sharedui.resources.voice_settings_testing
import com.letta.mobile.sharedui.resources.voice_settings_url
import com.letta.mobile.sharedui.resources.voice_settings_url_help
import com.letta.mobile.sharedui.resources.voice_settings_url_placeholder
import com.letta.mobile.sharedui.resources.voice_share_ask_admin
import com.letta.mobile.sharedui.resources.voice_share_confirm
import com.letta.mobile.sharedui.resources.voice_share_confirm_message
import com.letta.mobile.sharedui.resources.voice_share_confirm_title
import com.letta.mobile.sharedui.resources.voice_share_done
import com.letta.mobile.sharedui.resources.voice_share_help
import com.letta.mobile.sharedui.resources.voice_share_remove_local
import com.letta.mobile.sharedui.resources.voice_share_remove_local_help
import com.letta.mobile.sharedui.resources.voice_share_title
import com.letta.mobile.sharedui.resources.voice_share_without_key
import com.letta.mobile.ui.chat.voice.VoiceSetupAccess
import com.letta.mobile.ui.chat.voice.voiceErrorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

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
    host.supported && host.status != null && !host.configured && host.admin == true && local.endpoint.baseUrl == null ->
        VoiceSettingsMode.HOST_SETUP
    else -> VoiceSettingsMode.LOCAL
}

/** A host request's failure as the words the settings show; a lost revision race says to try again. */
private fun hostFailureText(code: HostVoiceErrorCode): StringResource =
    if (code == HostVoiceErrorCode.REVISION_CONFLICT) Res.string.voice_host_conflict else voiceErrorText(code.voiceCode)

/**
 * The admin's editor for the shared setup, kept in memory only: a typed key never enters saved UI
 * state, and it leaves the device once, inside the write-only setup command.
 */
@Stable
private class HostVoiceForm(private val shared: SharedVoiceSetup, status: HostVoiceSetupStatus?, private val scope: CoroutineScope) {
    var url: String by mutableStateOf(status?.baseUrl.orEmpty())
    var key: String by mutableStateOf("")
    var transcriptionModel: String by mutableStateOf(status?.transcription?.model.orEmpty())
    var speechModel: String by mutableStateOf(status?.speech?.model.orEmpty())
    var voice: String by mutableStateOf(status?.speech?.voice.orEmpty())
    var grantAudio: Boolean by mutableStateOf(status?.consent?.grants?.audioForTranscription == true)
    var grantText: Boolean by mutableStateOf(status?.consent?.grants?.textForSynthesis == true)
    var outcome: VoiceOutcome? by mutableStateOf(null)
    var busy: Boolean by mutableStateOf(false)

    /** The address problem under the endpoint client's rules, counting a typed key. */
    val problem: VoiceErrorCode? get() = VoiceEndpointRules.problem(url, withKey = key.isNotBlank())

    fun edit(change: HostVoiceForm.() -> Unit) {
        change()
        outcome = null
    }

    fun save(onSaved: ((ready: Boolean) -> Unit)?) = launchShowing {
        val typedKey = key.trim().takeIf { it.isNotEmpty() }
        val keyChange = typedKey?.let { HostVoiceKeyChange.Replace(SecretWriteValue(it)) } ?: HostVoiceKeyChange.Keep
        val choice = HostVoiceEndpointChoice(
            SpeechEndpointUrl(url.trim()),
            transcriptionModel.trim().takeIf { it.isNotEmpty() }?.let(::SpeechModelName),
            speechModel.trim().takeIf { it.isNotEmpty() }?.let(::SpeechModelName),
            voice.trim().takeIf { it.isNotEmpty() }?.let(::SpeechVoiceName),
        )
        val grants = HostVoiceGrants(audioForTranscription = grantAudio, textForSynthesis = grantText)
        val status = shared.write(HostVoiceSetupCommand(choice, keyChange, grants, shared.state.value.status?.revision))
        key = ""
        if (status == null) return@launchShowing failed()
        onSaved?.invoke(status.speech.available)
        VoiceOutcome(Res.string.voice_settings_saved, SettingsTone.Positive)
    }

    fun test() = launchShowing {
        val stored = shared.state.value.status?.baseUrl
        val candidate = if (url.trim() == stored && key.isBlank()) null else HostVoiceConnectionCandidate(
            SpeechEndpointUrl(url.trim()),
            key.trim().takeIf { it.isNotEmpty() }?.let(::SecretWriteValue),
        )
        val result = shared.test(candidate)
        if (result == null) failed() else VoiceOutcome(Res.string.voice_settings_reachable, SettingsTone.Positive)
    }

    fun clear() = launchShowing {
        if (shared.clear() == null) failed() else null
    }

    private suspend fun failed(): VoiceOutcome {
        val code = shared.state.value.error ?: HostVoiceErrorCode.INTERNAL
        if (code == HostVoiceErrorCode.REVISION_CONFLICT) shared.refresh()
        return VoiceOutcome(hostFailureText(code), SettingsTone.Error)
    }

    /** Runs [request] in the section's scope, showing its outcome. */
    private fun launchShowing(request: suspend () -> VoiceOutcome?) {
        busy = true
        scope.launch {
            outcome = request()
            busy = false
        }
    }
}

/**
 * The host's shared speech server as the host reports it: its address, whether it keeps a key,
 * listening and speaking models, and whether it answered. An admin device edits it below; others
 * see a note. [onSaved] hears the acknowledgement being given (voice mode may now start). This
 * device's own choices follow: its audio acknowledgement (where it listens through
 * the server) and whether to use its own server instead.
 */
@Composable
internal fun SettingsScope.SharedVoiceRows(
    access: VoiceSetupAccess,
    host: HostVoiceState,
    local: VoiceSetup,
    onSaved: ((ready: Boolean) -> Unit)?,
) {
    val shared = access.shared ?: return
    val status = host.status ?: return
    NoteRow(SettingsRow.Note(VoiceSettingsKeys.HOST_NOTE, SettingsStatus(stringResource(Res.string.voice_host_note))))
    HostVoiceFacts(status)
    if (!status.speech.available) {
        val incomplete = SettingsStatus(stringResource(Res.string.voice_host_incomplete), SettingsTone.Warning)
        NoteRow(SettingsRow.Note(VoiceSettingsKeys.HOST_INCOMPLETE, incomplete))
    }
    VoiceAcknowledgementRow(access, host, local) { onSaved?.invoke(true) }
    when (host.admin) {
        true -> HostVoiceEditor(shared, status, onSaved = null)
        false -> {
            val readOnly = SettingsStatus(stringResource(Res.string.voice_host_read_only))
            NoteRow(SettingsRow.Note(VoiceSettingsKeys.HOST_READ_ONLY, readOnly))
        }
        null -> Unit
    }
    if (local.endpoint.baseUrl != null) {
        val row = SettingsRow.Toggle(
            key = VoiceSettingsKeys.DEVICE_ONLY,
            title = stringResource(Res.string.voice_host_device_only),
            checked = local.deviceOnly,
            supporting = stringResource(Res.string.voice_host_device_only_help),
        ) { checked -> access.store.setDeviceOnly(checked) }
        ToggleRow(row)
    }
    if (shareOfferFor(local, host) == VoiceShareOffer.REMOVE_LOCAL) {
        val remove = SettingsRow.Link(
            key = VoiceSettingsKeys.REMOVE_LOCAL,
            title = stringResource(Res.string.voice_share_remove_local),
            supporting = stringResource(Res.string.voice_share_remove_local_help),
        ) { access.migration?.removeLocal() }
        LinkRow(remove)
    }
}

/** The shared server's address, key presence, models and reachability, as the host reports them. */
@Composable
private fun SettingsScope.HostVoiceFacts(status: HostVoiceSetupStatus) {
    val notSet = stringResource(Res.string.voice_host_not_set)
    InfoRow(SettingsRow.Info(VoiceSettingsKeys.HOST_SERVER, stringResource(Res.string.voice_host_server), status.baseUrl.orEmpty()))
    val key = stringResource(if (status.hasApiKey) Res.string.voice_host_key_stored else Res.string.voice_host_key_none)
    InfoRow(SettingsRow.Info(VoiceSettingsKeys.HOST_KEY, stringResource(Res.string.voice_host_key), key))
    val listening = status.transcription.model?.takeIf { status.transcription.available } ?: notSet
    InfoRow(SettingsRow.Info(VoiceSettingsKeys.HOST_LISTENING, stringResource(Res.string.voice_host_listening), listening))
    val speaking = listOfNotNull(status.speech.model, status.speech.voice).joinToString(" · ").takeIf { status.speech.available } ?: notSet
    InfoRow(SettingsRow.Info(VoiceSettingsKeys.HOST_SPEAKING, stringResource(Res.string.voice_host_speaking), speaking))
    val reach = status.reachability
    val reachStatus = when (reach.state) {
        HostVoiceReachabilityState.REACHABLE -> SettingsStatus(stringResource(Res.string.voice_host_reachable), SettingsTone.Positive)
        HostVoiceReachabilityState.FAILED -> SettingsStatus(
            stringResource(voiceErrorText((reach.error ?: HostVoiceErrorCode.INTERNAL).voiceCode)),
            SettingsTone.Error,
        )
        HostVoiceReachabilityState.UNKNOWN -> SettingsStatus(stringResource(Res.string.voice_host_unchecked))
    }
    StatusRow(SettingsRow.Status(VoiceSettingsKeys.HOST_REACHABILITY, stringResource(Res.string.voice_host_reachability), reachStatus))
}

/**
 * This device's one-time acknowledgement that its recordings go to the shared server, shown where
 * the shared setup listens; it names the server's origin and is stored on this device only.
 */
@Composable
private fun SettingsScope.VoiceAcknowledgementRow(
    access: VoiceSetupAccess,
    host: HostVoiceState,
    local: VoiceSetup,
    onGiven: () -> Unit,
) {
    val origin = host.origin ?: return
    if (host.status?.transcription?.available != true) return
    val row = SettingsRow.Toggle(
        key = VoiceSettingsKeys.HOST_ACK,
        title = stringResource(Res.string.voice_host_ack),
        checked = local.hostAudioAck == origin,
        supporting = stringResource(Res.string.voice_host_ack_help, origin.value),
    ) { checked ->
        access.store.acknowledgeHostAudio(origin.takeIf { checked })
        if (checked) onGiven()
    }
    ToggleRow(row)
}

/** Setting up the shared server from an admin device whose host has none and which has none of its own. */
@Composable
internal fun SettingsScope.HostVoiceSetupRows(shared: SharedVoiceSetup, onSaved: ((ready: Boolean) -> Unit)?) {
    NoteRow(SettingsRow.Note(VoiceSettingsKeys.HOST_NOTE, SettingsStatus(stringResource(Res.string.voice_host_setup_note))))
    HostVoiceEditor(shared, shared.state.value.status, onSaved)
}

/** The admin's editor: address, write-only key, models and voice, the shared grants, Save, Test and Remove. */
@Composable
private fun SettingsScope.HostVoiceEditor(shared: SharedVoiceSetup, status: HostVoiceSetupStatus?, onSaved: ((ready: Boolean) -> Unit)?) {
    val scope = rememberCoroutineScope()
    val form = remember(shared, status?.revision) { HostVoiceForm(shared, status, scope) }
    HostVoiceAddressRows(form)
    HostVoiceNameRows(form)
    HostVoiceGrantRows(form)
    val usable = form.problem == null && !form.busy
    val testLabel = stringResource(if (form.busy) Res.string.voice_settings_testing else Res.string.voice_settings_test)
    val saveLabel = stringResource(Res.string.voice_host_save)
    val save = ActionButton(VoiceSettingsKeys.HOST_SAVE, saveLabel, { form.save(onSaved) }, primary = true, enabled = usable)
    val test = ActionButton(VoiceSettingsKeys.HOST_TEST, testLabel, form::test, enabled = usable)
    ActionRow("voice-host-actions", listOf(save, test))
    form.outcome?.let { NoteRow(SettingsRow.Note(VoiceSettingsKeys.HOST_STATUS, SettingsStatus(stringResource(it.message), it.tone))) }
    if (status?.configured == true) {
        val confirm = SettingsConfirmText(
            stringResource(Res.string.voice_host_clear_confirm_title),
            stringResource(Res.string.voice_host_clear_confirm_message),
            stringResource(Res.string.voice_host_clear_confirm),
        )
        val clearLabel = stringResource(Res.string.voice_host_clear)
        LinkRow(SettingsRow.Link(VoiceSettingsKeys.HOST_CLEAR, clearLabel, destructive = true, confirm = confirm) { form.clear() })
    }
}

@Composable
private fun SettingsScope.HostVoiceAddressRows(form: HostVoiceForm) {
    val problemText = form.problem?.let { stringResource(voiceErrorText(it)) }
    val urlRow = SettingsRow.Text(
        key = VoiceSettingsKeys.HOST_URL,
        title = stringResource(Res.string.voice_settings_url),
        placeholder = stringResource(Res.string.voice_settings_url_placeholder),
        supporting = stringResource(Res.string.voice_settings_url_help),
        problem = { problemText },
    )
    TextFieldRow(urlRow, form.url) { form.edit { url = it } }
    val keyRow = SettingsRow.Text(
        key = VoiceSettingsKeys.HOST_KEY_FIELD,
        title = stringResource(Res.string.voice_host_key_field),
        supporting = stringResource(Res.string.voice_host_key_field_help),
        secret = true,
    )
    TextFieldRow(keyRow, form.key) { form.edit { key = it } }
}

@Composable
private fun SettingsScope.HostVoiceNameRows(form: HostVoiceForm) {
    val transcriptionTitle = stringResource(Res.string.voice_host_transcription_model)
    val transcription = SettingsRow.Text(VoiceSettingsKeys.HOST_TRANSCRIPTION_MODEL, transcriptionTitle)
    TextFieldRow(transcription, form.transcriptionModel) { form.edit { transcriptionModel = it } }
    val speech = SettingsRow.Text(VoiceSettingsKeys.HOST_SPEECH_MODEL, stringResource(Res.string.voice_host_speech_model))
    TextFieldRow(speech, form.speechModel) { form.edit { speechModel = it } }
    val voice = SettingsRow.Text(VoiceSettingsKeys.HOST_VOICE, stringResource(Res.string.voice_host_voice))
    TextFieldRow(voice, form.voice) { form.edit { this.voice = it } }
}

@Composable
private fun SettingsScope.HostVoiceGrantRows(form: HostVoiceForm) {
    val help = stringResource(Res.string.voice_host_grant_help)
    val audioTitle = stringResource(Res.string.voice_host_grant_audio)
    val audio = SettingsRow.Toggle(VoiceSettingsKeys.HOST_GRANT_AUDIO, audioTitle, form.grantAudio, help) {
        form.edit { grantAudio = it }
    }
    ToggleRow(audio)
    val text = SettingsRow.Toggle(VoiceSettingsKeys.HOST_GRANT_TEXT, stringResource(Res.string.voice_host_grant_text), form.grantText) {
        form.edit { grantText = it }
    }
    ToggleRow(text)
}

/**
 * What the section offers about this device's own server while the host shares none: an admin
 * device shares it with every device in one tap (with its key, after a confirmation that names
 * what happens to it, or without); another device is told an admin device can.
 */
@Composable
internal fun SettingsScope.VoiceShareRows(access: VoiceSetupAccess, host: HostVoiceState, local: VoiceSetup) {
    val migration = access.migration ?: return
    when (shareOfferFor(local, host)) {
        VoiceShareOffer.SHARE -> VoiceShareActions(access, migration.hasKey)
        VoiceShareOffer.ASK_ADMIN ->
            NoteRow(SettingsRow.Note(VoiceSettingsKeys.SHARE, SettingsStatus(stringResource(Res.string.voice_share_ask_admin))))
        VoiceShareOffer.NONE, VoiceShareOffer.REMOVE_LOCAL -> Unit
    }
}

@Composable
private fun SettingsScope.VoiceShareActions(access: VoiceSetupAccess, hasKey: Boolean) {
    val migration = access.migration ?: return
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<VoiceOutcome?>(null) }
    val share = { sendKey: Boolean ->
        scope.launch {
            val code = migration.share(sendKey)
            outcome = code?.let { VoiceOutcome(hostFailureText(it), SettingsTone.Error) }
                ?: VoiceOutcome(Res.string.voice_share_done, SettingsTone.Positive)
        }
        Unit
    }
    val confirm = if (hasKey) {
        SettingsConfirmText(
            stringResource(Res.string.voice_share_confirm_title),
            stringResource(Res.string.voice_share_confirm_message),
            stringResource(Res.string.voice_share_confirm),
        )
    } else {
        null
    }
    val title = stringResource(Res.string.voice_share_title)
    val help = stringResource(Res.string.voice_share_help)
    LinkRow(SettingsRow.Link(VoiceSettingsKeys.SHARE, title, help, confirm = confirm) { share(hasKey) })
    if (hasKey) {
        LinkRow(SettingsRow.Link(VoiceSettingsKeys.SHARE_WITHOUT_KEY, stringResource(Res.string.voice_share_without_key)) { share(false) })
    }
    outcome?.let { NoteRow(SettingsRow.Note(VoiceSettingsKeys.SHARE_STATUS, SettingsStatus(stringResource(it.message), it.tone))) }
}
