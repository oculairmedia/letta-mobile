package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.catalog.SpeechCatalog
import com.letta.mobile.data.voice.catalog.SpeechCatalogException
import com.letta.mobile.data.voice.catalog.SpeechCatalogSource
import com.letta.mobile.data.voice.catalog.SpeechChoices
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_catalog_failed
import com.letta.mobile.sharedui.resources.voice_catalog_idle
import com.letta.mobile.sharedui.resources.voice_catalog_loading
import com.letta.mobile.sharedui.resources.voice_catalog_retry
import com.letta.mobile.sharedui.resources.voice_host_not_set
import com.letta.mobile.sharedui.resources.voice_host_speech_model
import com.letta.mobile.sharedui.resources.voice_host_transcription_model
import com.letta.mobile.sharedui.resources.voice_host_voice
import com.letta.mobile.sharedui.resources.voice_name_custom
import com.letta.mobile.sharedui.resources.voice_name_custom_field
import com.letta.mobile.sharedui.resources.voice_name_custom_placeholder
import com.letta.mobile.ui.chat.voice.voiceErrorText
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Which name a picker chooses on the speech server, its title, and its row key on this device's own server and the shared one. */
internal enum class VoiceNameKind(val title: StringResource, val localKey: String, val hostKey: String) {
    TRANSCRIPTION(Res.string.voice_host_transcription_model, VoiceSettingsKeys.TRANSCRIPTION_MODEL, VoiceSettingsKeys.HOST_TRANSCRIPTION_MODEL),
    SPEECH(Res.string.voice_host_speech_model, VoiceSettingsKeys.SPEECH_MODEL, VoiceSettingsKeys.HOST_SPEECH_MODEL),
    VOICE(Res.string.voice_host_voice, VoiceSettingsKeys.VOICE, VoiceSettingsKeys.HOST_VOICE),
    ;

    /** The names to offer from [catalog], always with [selected]. */
    fun choices(catalog: SpeechCatalog, selected: String): List<String> = when (this) {
        TRANSCRIPTION -> SpeechChoices.transcriptionModels(catalog.models, selected)
        SPEECH -> SpeechChoices.speechModels(catalog.models, selected)
        VOICE -> SpeechChoices.voices(catalog.voices, selected)
    }
}

/** The model and voice names a form is editing, and which of them the person types as a custom name. In memory only. */
@Stable
internal class VoiceNamesDraft(transcription: String, speech: String, voice: String) {
    private val values = mutableStateMapOf(
        VoiceNameKind.TRANSCRIPTION to transcription,
        VoiceNameKind.SPEECH to speech,
        VoiceNameKind.VOICE to voice,
    )
    private val custom = mutableStateMapOf<VoiceNameKind, Boolean>()

    /** The name chosen for [kind], as picked or typed. */
    operator fun get(kind: VoiceNameKind): String = values[kind].orEmpty()

    /** [name], picked from the server's list for [kind]. */
    fun pick(kind: VoiceNameKind, name: String) {
        values[kind] = name
        custom.remove(kind)
    }

    /** [name], typed for [kind]. */
    fun type(kind: VoiceNameKind, name: String) {
        values[kind] = name
    }

    /** The person chose to type [kind]'s name: its custom field shows. */
    fun startCustom(kind: VoiceNameKind) {
        custom[kind] = true
    }

    /** Whether [kind]'s custom field shows. */
    fun isCustom(kind: VoiceNameKind): Boolean = custom[kind] == true
}

/** How far the server's list is: not asked (nothing saved to ask), loading, listed, or failed. */
internal enum class VoiceCatalogPhase { IDLE, LOADING, LOADED, FAILED }

/** The server's list as the Voice section knows it; [retry] asks again. */
@Stable
internal class VoiceCatalogState {
    var phase: VoiceCatalogPhase by mutableStateOf(VoiceCatalogPhase.IDLE)
        private set
    var catalog: SpeechCatalog? by mutableStateOf(null)
        private set
    var error: VoiceErrorCode? by mutableStateOf(null)
        private set
    var attempt: Int by mutableIntStateOf(0)
        private set

    /** Asks the server again. */
    fun retry() {
        attempt++
    }

    /** Shows [next], with what it listed or why it failed. */
    fun show(next: VoiceCatalogPhase, listed: SpeechCatalog? = null, failure: VoiceErrorCode? = null) {
        phase = next
        catalog = listed
        error = failure
    }
}

/**
 * The server's list through [source], asked again whenever [key] changes, a source appears or goes,
 * or the person retries; no source (nothing saved to ask) is the not-asked phase.
 */
@Composable
internal fun rememberVoiceCatalog(key: Any?, source: SpeechCatalogSource?): VoiceCatalogState {
    val state = remember { VoiceCatalogState() }
    val current by rememberUpdatedState(source)
    LaunchedEffect(key, source != null, state.attempt) {
        val listing = current ?: return@LaunchedEffect state.show(VoiceCatalogPhase.IDLE)
        state.show(VoiceCatalogPhase.LOADING)
        try {
            state.show(VoiceCatalogPhase.LOADED, listed = listing.load())
        } catch (failure: SpeechCatalogException) {
            state.show(VoiceCatalogPhase.FAILED, failure = failure.code)
        }
    }
    return state
}

/**
 * The transcription model, speech model and voice, picked from what the server lists: the design
 * system's choice row (a sheet on touch, an anchored list on a pointer, searchable when long), always
 * offering the saved name and a "Custom…" entry that opens a field for any name, so a server that
 * lists nothing useful still works. While the list loads the pickers say so; when it failed, or there
 * is no saved server to ask, they are plain fields, with a note and (after a failure) Try again.
 * [host] picks the shared editor's row keys; [onEdit] hears every change.
 */
@Composable
internal fun SettingsScope.VoiceNameRows(names: VoiceNamesDraft, catalog: VoiceCatalogState, host: Boolean, onEdit: () -> Unit) {
    VoiceNameKind.entries.forEach { kind ->
        VoiceNameRow(kind, if (host) kind.hostKey else kind.localKey, names, catalog, onEdit)
    }
    val first = VoiceNameKind.TRANSCRIPTION.let { if (host) it.hostKey else it.localKey }
    VoiceCatalogNote(first, catalog)
}

@Composable
private fun SettingsScope.VoiceNameRow(kind: VoiceNameKind, key: String, names: VoiceNamesDraft, catalog: VoiceCatalogState, onEdit: () -> Unit) {
    val value = names[kind]
    val title = stringResource(kind.title)
    val type: (String) -> Unit = {
        names.type(kind, it)
        onEdit()
    }
    val listed = catalog.catalog
    val loading = catalog.phase == VoiceCatalogPhase.LOADING
    if (listed == null && !loading) {
        TextFieldRow(SettingsRow.Text(key, title), value, onValueChange = type)
        return
    }
    val chosen = value.trim()
    val custom = names.isCustom(kind)
    val choices = listed?.let { kind.choices(it, chosen) } ?: listOfNotNull(chosen.takeIf { it.isNotEmpty() })
    val options = choices.map { SettingsOption(it, it) } + SettingsOption(CUSTOM_OPTION, stringResource(Res.string.voice_name_custom))
    val supporting = when {
        loading -> stringResource(Res.string.voice_catalog_loading)
        chosen.isEmpty() && !custom -> stringResource(Res.string.voice_host_not_set)
        else -> null
    }
    val row = SettingsRow.Choice(
        key = key,
        title = title,
        options = options,
        selected = if (custom) CUSTOM_OPTION else chosen,
        supporting = supporting,
        alwaysList = true,
    ) { picked ->
        if (picked == CUSTOM_OPTION) names.startCustom(kind) else names.pick(kind, picked)
        onEdit()
    }
    ChoiceRow(row)
    if (custom) {
        val field = SettingsRow.Text(
            key = key + VoiceSettingsKeys.CUSTOM_SUFFIX,
            title = stringResource(Res.string.voice_name_custom_field),
            placeholder = stringResource(Res.string.voice_name_custom_placeholder),
        )
        TextFieldRow(field, value, onValueChange = type)
    }
}

/** Under the pickers: that the list failed (with Try again and why), or that a saved server is needed to list one. */
@Composable
private fun SettingsScope.VoiceCatalogNote(key: String, catalog: VoiceCatalogState) {
    when (catalog.phase) {
        VoiceCatalogPhase.FAILED -> {
            val failed = SettingsStatus(stringResource(Res.string.voice_catalog_failed), SettingsTone.Warning)
            NoteRow(SettingsRow.Note(key + VoiceSettingsKeys.CATALOG_SUFFIX, failed))
            val reason = catalog.error?.let { stringResource(voiceErrorText(it)) }
            val retry = SettingsRow.Link(key + VoiceSettingsKeys.CATALOG_RETRY_SUFFIX, stringResource(Res.string.voice_catalog_retry), reason) {
                catalog.retry()
            }
            LinkRow(retry)
        }
        VoiceCatalogPhase.IDLE -> {
            val idle = SettingsStatus(stringResource(Res.string.voice_catalog_idle))
            NoteRow(SettingsRow.Note(key + VoiceSettingsKeys.CATALOG_SUFFIX, idle))
        }
        VoiceCatalogPhase.LOADING, VoiceCatalogPhase.LOADED -> Unit
    }
}

/** The option key of the "Custom…" entry; not a name a server would list. */
private const val CUSTOM_OPTION = "voice-custom-name"
