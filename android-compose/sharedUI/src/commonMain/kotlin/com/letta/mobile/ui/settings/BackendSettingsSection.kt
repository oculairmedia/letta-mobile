package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.BackendServerUrlCheck
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.settings.BackendConnectionTester
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import kotlinx.coroutines.launch

/**
 * What the Backend section asks its host to do. A null [onIrohIdentityReset] hides the reset (the
 * host keeps no Iroh identity); a null [testConnection] hides Test connection.
 */
data class BackendSettingsActions(
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit = {},
    val onIrohIdentityReset: (() -> Unit)? = null,
    val testConnection: BackendConnectionTester? = null,
)

/**
 * The backend form's unsaved values. [problem] is what is wrong with the address for the chosen
 * mode (shared with every client through [BackendServerUrlCheck]); the form saves only without one.
 */
@Immutable
data class BackendSettingsForm(
    val serverUrl: String,
    val mode: LettaConfig.Mode,
    val tokenInput: String = "",
) {
    val problem: String? get() = BackendServerUrlCheck.problem(mode, serverUrl)

    /** [saved] with this form applied; an empty token field keeps the saved token. Saving clears a migration notice. */
    fun applyTo(saved: LettaConfig): LettaConfig = saved.copy(
        mode = mode,
        serverUrl = serverUrl.trim(),
        accessToken = tokenInput.trim().takeIf { it.isNotEmpty() } ?: saved.accessToken,
        migrationNotice = null,
    )

    companion object {
        fun of(config: LettaConfig) = BackendSettingsForm(serverUrl = config.serverUrl, mode = config.mode)
    }
}

object BackendSettingsTags {
    const val SECTION = "backend"
    const val MODE = "backend-mode"
    const val SERVER_URL = "backend-server-url"
    const val TOKEN = "backend-token"
    const val SAVE = "backend-save"
    const val TEST = "backend-test"
    const val NOTICE = "backend-notice"
    const val TEST_RESULT = "backend-test-result"

    /** The tag of the address field's problem line. */
    const val PROBLEM = SettingsSectionTags.PROBLEM_PREFIX + SERVER_URL
}

/** The mode's name as the Settings page shows it (letta-mobile-ckmub.9: Local runtime | Remote host). */
val LettaConfig.Mode.settingsLabel: String
    get() = when (this) {
        LettaConfig.Mode.LOCAL -> "Local runtime"
        LettaConfig.Mode.SELF_HOSTED -> "Remote host"
    }

/** What the mode means, under its name. */
val LettaConfig.Mode.settingsDescription: String
    get() = when (this) {
        LettaConfig.Mode.LOCAL -> "The letta-code runtime on this device; no address needed."
        LettaConfig.Mode.SELF_HOSTED -> "A letta-code App Server on another machine, over Iroh (iroh://…) or a WebSocket (ws://…)."
    }

/** What the address field suggests for a remote host. */
const val REMOTE_HOST_PLACEHOLDER = "iroh://<endpoint id>@host:port, iroh://<ticket> or ws://host:port"

/**
 * letta-mobile-ckmub.3.12 / ckmub.9: the Backend section every client's Settings page shows, built
 * from the shared rows: the mode (Local runtime or Remote host, nothing else), the remote host's
 * address and access token, and Save. The address is checked as it is typed, so a bare Iroh endpoint
 * id or a malformed ticket is explained here instead of failing to dial. [form] is hoisted to the
 * page so the sections that only apply to one mode follow the unsaved choice.
 */
@Composable
fun BackendSettingsSection(
    config: LettaConfig,
    form: BackendSettingsForm,
    onFormChange: (BackendSettingsForm) -> Unit,
    actions: BackendSettingsActions,
    modifier: Modifier = Modifier,
) {
    var testResult by remember(form) { mutableStateOf<SettingsStatus?>(null) }
    val scope = rememberCoroutineScope()
    val remote = form.mode == LettaConfig.Mode.SELF_HOSTED
    SettingsSection(key = BackendSettingsTags.SECTION, title = "Backend", modifier = modifier) {
        config.migrationNotice?.let { NoteRow(BackendSettingsTags.NOTICE, SettingsStatus(it, SettingsTone.Warning)) }
        ChoiceRow(
            key = BackendSettingsTags.MODE,
            title = "Mode",
            options = LettaConfig.Mode.entries.map { SettingsOption(it.name, it.settingsLabel, it.settingsDescription) },
            selected = form.mode.name,
            onSelect = { picked -> onFormChange(form.copy(mode = LettaConfig.Mode.valueOf(picked))) },
        )
        if (remote) {
            TextFieldRow(
                key = BackendSettingsTags.SERVER_URL,
                title = "Address",
                value = form.serverUrl,
                onValueChange = { onFormChange(form.copy(serverUrl = it)) },
                options = TextFieldOptions(placeholder = REMOTE_HOST_PLACEHOLDER, problem = form.problem),
            )
            TextFieldRow(
                key = BackendSettingsTags.TOKEN,
                title = "Access token",
                value = form.tokenInput,
                onValueChange = { onFormChange(form.copy(tokenInput = it)) },
                options = TextFieldOptions(
                    placeholder = if (config.accessToken != null) "Saved token hidden" else "Optional",
                    supporting = "Only if the App Server asks for one.",
                    secret = true,
                ),
            )
        }
        ActionRow("backend-actions", backendButtons(config, form, actions, onFormChange) { tester ->
            testResult = SettingsStatus("Testing…")
            scope.launch { testResult = tester.test(form.serverUrl.trim(), form.tokenInput.trim().ifEmpty { null } ?: config.accessToken) }
        })
        testResult?.let { NoteRow(BackendSettingsTags.TEST_RESULT, it) }
    }
}

private fun backendButtons(
    config: LettaConfig,
    form: BackendSettingsForm,
    actions: BackendSettingsActions,
    onFormChange: (BackendSettingsForm) -> Unit,
    onTest: (BackendConnectionTester) -> Unit,
): List<ActionButton> = buildList {
    val remote = form.mode == LettaConfig.Mode.SELF_HOSTED
    add(
        ActionButton(
            key = BackendSettingsTags.SAVE,
            label = "Save",
            primary = true,
            enabled = form.problem == null,
            onClick = {
                actions.onConfigSaved(form.applyTo(config))
                onFormChange(form.copy(tokenInput = ""))
            },
        ),
    )
    val tester = actions.testConnection
    if (remote && tester != null) {
        add(ActionButton(BackendSettingsTags.TEST, "Test connection", onClick = { onTest(tester) }, enabled = form.serverUrl.isNotBlank()))
    }
    if (remote && config.accessToken != null) {
        add(
            ActionButton("backend-clear-token", "Clear token", onClick = {
                onFormChange(form.copy(tokenInput = ""))
                actions.onTokenCleared()
            }),
        )
    }
    actions.onIrohIdentityReset?.let { reset -> add(ActionButton("backend-reset-identity", "Reset Iroh identity", onClick = reset)) }
}
