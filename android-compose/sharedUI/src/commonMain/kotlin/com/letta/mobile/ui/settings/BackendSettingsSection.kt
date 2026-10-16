package com.letta.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.BackendServerUrlCheck
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.settings.BackendConnectionTester
import com.letta.mobile.data.settings.SettingsConfirmText
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * What the Backend section asks its host to do. A null [onIrohIdentityReset] hides the reset (the
 * host keeps no Iroh identity); a null [testConnection] hides Test connection; a null
 * [onScanToConnect] hides Scan to connect (no camera here).
 */
data class BackendSettingsActions(
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit = {},
    val onIrohIdentityReset: (() -> Unit)? = null,
    val testConnection: BackendConnectionTester? = null,
    val onScanToConnect: (() -> Unit)? = null,
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

    /** Whether saving would change [saved]: an edit or a typed token. */
    fun isDirty(saved: LettaConfig): Boolean =
        mode != saved.mode || serverUrl.trim() != saved.serverUrl || tokenInput.isNotBlank()

    /** [saved] with this form applied; an empty token field keeps the saved token. */
    fun applyTo(saved: LettaConfig): LettaConfig = saved.copy(
        mode = mode,
        serverUrl = serverUrl.trim(),
        accessToken = tokenInput.trim().takeIf { it.isNotEmpty() } ?: saved.accessToken,
    )

    companion object {
        fun of(config: LettaConfig) = BackendSettingsForm(serverUrl = config.serverUrl, mode = config.mode)
    }
}

object BackendSettingsTags {
    const val SECTION = "backend"
    const val MODE = "backend-mode"
    const val STATUS = "backend-status"
    const val SERVER_URL = "backend-server-url"
    const val TOKEN = "backend-token"
    const val SCAN = "backend-scan"
    const val SAVE = "backend-save"
    const val TEST = "backend-test"
    const val TEST_RESULT = "backend-test-result"
    const val DANGER = "danger"
    const val CLEAR_TOKEN = "backend-clear-token"
    const val RESET_IDENTITY = "backend-reset-identity"

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
        LettaConfig.Mode.LOCAL -> "letta-code on this device; no address needed"
        LettaConfig.Mode.SELF_HOSTED -> "A letta-code host elsewhere, over Iroh or WebSocket"
    }

/** What the address field suggests for a remote host. */
const val REMOTE_HOST_PLACEHOLDER = "iroh://<endpoint id>@host:port, iroh://<ticket> or ws://host:port"

/** The Backend form, the saved config it edits and the live connection; [onFormChange] keeps the form hoisted to the page. */
class BackendFormState(
    val config: LettaConfig,
    val form: BackendSettingsForm,
    val connection: BackendConnectionStatus? = null,
    val onFormChange: (BackendSettingsForm) -> Unit,
)

/** The connection as the Status row says it: words a person can act on, never colour alone. */
fun BackendConnectionStatus.settingsStatus(): SettingsStatus = when (this) {
    BackendConnectionStatus.Unconfigured -> SettingsStatus("Not connected")
    BackendConnectionStatus.Connecting -> SettingsStatus("Connecting…", SettingsTone.Warning)
    is BackendConnectionStatus.Connected -> SettingsStatus("Connected", SettingsTone.Positive)
    is BackendConnectionStatus.Failed -> SettingsStatus("Can't reach host", SettingsTone.Error)
}

/** The Status row's helper: the transport in use, or why the host can't be reached. */
fun BackendConnectionStatus.settingsHelper(): String? = when (this) {
    is BackendConnectionStatus.Connected -> "Over $transport"
    is BackendConnectionStatus.Failed -> message
    BackendConnectionStatus.Unconfigured, BackendConnectionStatus.Connecting -> null
}

/** Where a Test connection stands. */
sealed interface ConnectionTest {
    data object Idle : ConnectionTest

    data object Testing : ConnectionTest

    /** The probe's own words, and how long it took. */
    data class Done(val result: SettingsStatus, val millis: Long) : ConnectionTest {
        /** The short trailing badge: the latency when the host answered, else what went wrong. */
        val badge: SettingsStatus
            get() = when (result.tone) {
                SettingsTone.Positive -> SettingsStatus("Reachable · $millis ms", SettingsTone.Positive)
                SettingsTone.Error -> SettingsStatus("Can't reach", SettingsTone.Error)
                SettingsTone.Normal, SettingsTone.Warning -> SettingsStatus("Not checked", SettingsTone.Warning)
            }
    }
}

/**
 * letta-mobile-ckmub.3.12 / ckmub.9 / ckmub.3.12.1: the Backend section every client's Settings page
 * shows. A segmented Local runtime | Remote host; for a remote host the connection's status, the
 * address (checked as it is typed, with Paste and Scan to connect), the access token, and Test
 * connection with its latency or the reason it failed; then an explicit Save that waits for a change.
 * The form is hoisted to the page so the sections that only apply to one mode follow the unsaved choice.
 */
@Composable
fun BackendSettingsSection(state: BackendFormState, actions: BackendSettingsActions, modifier: Modifier = Modifier) {
    val form = state.form
    var test by remember(form) { mutableStateOf<ConnectionTest>(ConnectionTest.Idle) }
    val scope = rememberCoroutineScope()
    val onTest: (BackendConnectionTester) -> Unit = { tester ->
        test = ConnectionTest.Testing
        scope.launch {
            val started = TimeSource.Monotonic.markNow()
            val result = tester.test(form.serverUrl.trim(), form.tokenInput.trim().ifEmpty { null } ?: state.config.accessToken)
            test = ConnectionTest.Done(result, started.elapsedNow().inWholeMilliseconds)
        }
    }
    SettingsSection(SettingsHeader(BackendSettingsTags.SECTION, "Backend", showTitle = false), modifier) {
        ChoiceRow(modeRow(state))
        state.connection?.let { StatusRow(SettingsRow.Status(SettingsCopy.STATUS.key, SettingsCopy.STATUS.title, it.settingsStatus(), it.settingsHelper())) }
        if (form.mode == LettaConfig.Mode.SELF_HOSTED) {
            RemoteHostRows(state, actions)
            actions.testConnection?.let { tester -> TestConnectionRow(test, enabled = form.serverUrl.isNotBlank()) { onTest(tester) } }
            (test as? ConnectionTest.Done)?.let { NoteRow(SettingsRow.Note(BackendSettingsTags.TEST_RESULT, it.result)) }
        }
        ActionRow("backend-actions", listOf(saveButton(state, actions)))
    }
}

@Composable
private fun SettingsScope.RemoteHostRows(state: BackendFormState, actions: BackendSettingsActions) {
    val form = state.form
    TextFieldRow(addressRow(form), form.serverUrl, paste = true) { state.onFormChange(form.copy(serverUrl = it)) }
    actions.onScanToConnect?.let { scan ->
        LinkRow(SettingsRow.Link(SettingsCopy.SCAN.key, SettingsCopy.SCAN.title, SettingsCopy.SCAN.helper, onClick = scan))
    }
    TextFieldRow(tokenRow(state.config), form.tokenInput, paste = true) { state.onFormChange(form.copy(tokenInput = it)) }
}

/** Test connection as a row: a spinner while it runs, then the latency or what went wrong. */
@Composable
private fun TestConnectionRow(test: ConnectionTest, enabled: Boolean, onTest: () -> Unit) {
    val label = SettingsCopy.TEST
    val runnable = enabled && test != ConnectionTest.Testing
    SettingsRowFrame(
        tag = SettingsSectionTags.ROW_PREFIX + label.key,
        words = RowWords(label.title, label.helper),
        enabled = runnable,
        interaction = Modifier.clickable(enabled = runnable, role = Role.Button, onClick = onTest),
    ) {
        when (test) {
            ConnectionTest.Idle -> Unit
            ConnectionTest.Testing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                CircularProgressIndicator(Modifier.size(LettaDimens.Control.iconSm), strokeWidth = LettaDimens.Space.hair)
                Text("Testing…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is ConnectionTest.Done -> SettingsStatusBadge(test.badge)
        }
    }
}

/**
 * letta-mobile-ckmub.3.12.1: the danger zone, last and on its own: removing the saved token and
 * resetting this device's identity, each in red and confirmed with its consequence first.
 */
@Composable
fun DangerZoneSection(state: BackendFormState, actions: BackendSettingsActions, modifier: Modifier = Modifier) {
    val hasToken = state.config.accessToken != null
    val reset = actions.onIrohIdentityReset
    if (!hasToken && reset == null) return
    SettingsSection(SettingsHeader(BackendSettingsTags.DANGER, "Danger zone", showTitle = false), modifier) {
        if (hasToken) {
            LinkRow(
                destructiveRow(SettingsCopy.CLEAR_TOKEN, CLEAR_TOKEN_CONFIRM) {
                    state.onFormChange(state.form.copy(tokenInput = ""))
                    actions.onTokenCleared()
                },
            )
        }
        reset?.let { LinkRow(destructiveRow(SettingsCopy.RESET_IDENTITY, RESET_IDENTITY_CONFIRM, it)) }
    }
}

private fun destructiveRow(label: SettingsLabel, confirm: SettingsConfirmText, onClick: () -> Unit) = SettingsRow.Link(
    key = label.key,
    title = label.title,
    supporting = label.helper,
    destructive = true,
    confirm = confirm,
    onClick = onClick,
)

private val CLEAR_TOKEN_CONFIRM = SettingsConfirmText(
    title = "Remove the access token?",
    message = "The saved token is deleted from this device. A host that asks for one won't connect until you enter it again.",
    confirmLabel = "Remove token",
)

private val RESET_IDENTITY_CONFIRM = SettingsConfirmText(
    title = "Reset this device's identity?",
    message = "This device gets a new Iroh identity. Hosts it paired with won't recognise it until you pair it again; this can't be undone.",
    confirmLabel = "Reset identity",
)

private fun modeRow(state: BackendFormState) = SettingsRow.Choice(
    key = SettingsCopy.MODE.key,
    title = SettingsCopy.MODE.title,
    options = MODE_ORDER.map { SettingsOption(it.name, it.settingsLabel, it.settingsDescription) },
    selected = state.form.mode.name,
    inline = true,
    onSelect = { picked -> state.onFormChange(state.form.copy(mode = LettaConfig.Mode.valueOf(picked))) },
)

/** Local runtime first, as the segmented control reads. */
private val MODE_ORDER = listOf(LettaConfig.Mode.LOCAL, LettaConfig.Mode.SELF_HOSTED)

private fun addressRow(form: BackendSettingsForm) = SettingsRow.Text(
    key = SettingsCopy.ADDRESS.key,
    title = SettingsCopy.ADDRESS.title,
    placeholder = REMOTE_HOST_PLACEHOLDER,
    problem = { form.problem },
)

private fun tokenRow(config: LettaConfig) = SettingsRow.Text(
    key = SettingsCopy.TOKEN.key,
    title = SettingsCopy.TOKEN.title,
    placeholder = if (config.accessToken != null) "Saved token hidden" else "Optional",
    supporting = SettingsCopy.TOKEN.helper,
    secret = true,
)

/** Save waits for a valid change (or a backend that was never set up). */
private fun saveButton(state: BackendFormState, actions: BackendSettingsActions): ActionButton {
    val form = state.form
    val changed = form.isDirty(state.config) || state.connection == BackendConnectionStatus.Unconfigured
    return ActionButton(
        key = BackendSettingsTags.SAVE,
        label = "Save",
        primary = true,
        enabled = form.problem == null && changed,
        onClick = {
            actions.onConfigSaved(form.applyTo(state.config))
            state.onFormChange(form.copy(tokenInput = ""))
        },
    )
}
