package com.letta.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.BackendServerUrlCheck
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.theme.LettaDimens

/** What the backend card asks its host to do. A null [onIrohIdentityReset] hides the reset (the host keeps no identity). */
data class BackendSettingsActions(
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit = {},
    val onIrohIdentityReset: (() -> Unit)? = null,
)

/**
 * The backend form's unsaved values. [problem] is what is wrong with the server URL for the chosen
 * mode (shared with every client through [BackendServerUrlCheck]); the form saves only without one.
 */
@Immutable
data class BackendSettingsForm(
    val serverUrl: String,
    val mode: LettaConfig.Mode,
    val tokenInput: String = "",
) {
    val problem: String? get() = BackendServerUrlCheck.problem(mode, serverUrl)

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
    const val CARD = "backend-settings-card"
    const val SERVER_URL = "backend-settings-server-url"
    const val SAVE = "backend-settings-save"
    const val PROBLEM = "backend-settings-problem"
}

/**
 * letta-mobile-ckmub.8: the Backend card every client's Settings page shows: the server URL, the
 * mode (Cloud, Self-hosted, Local runtime) and the access token. The URL is checked as it is typed,
 * so a bare Iroh endpoint id or a malformed ticket is explained here instead of failing to dial.
 */
@Composable
fun BackendSettingsCard(
    config: LettaConfig,
    actions: BackendSettingsActions,
    modifier: Modifier = Modifier,
) {
    var form by remember(config.id, config.serverUrl, config.mode) { mutableStateOf(BackendSettingsForm.of(config)) }
    Card(
        modifier = modifier.fillMaxWidth().testTag(BackendSettingsTags.CARD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            Text("Backend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ServerUrlField(form = form, onChange = { form = it })
            ModeSelector(mode = form.mode, onModeChange = { form = form.copy(mode = it) })
            TokenField(hasSavedToken = config.accessToken != null, value = form.tokenInput, onChange = { form = form.copy(tokenInput = it) })
            BackendSettingsButtons(
                canSave = form.problem == null,
                hasSavedToken = config.accessToken != null,
                buttons = BackendSettingsButtonActions(
                    onSave = {
                        actions.onConfigSaved(form.applyTo(config))
                        form = form.copy(tokenInput = "")
                    },
                    onClearToken = {
                        form = form.copy(tokenInput = "")
                        actions.onTokenCleared()
                    },
                    onResetIdentity = actions.onIrohIdentityReset,
                ),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServerUrlField(form: BackendSettingsForm, onChange: (BackendSettingsForm) -> Unit) {
    val problem = form.problem.takeIf { form.serverUrl.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        FieldLabel("Server URL")
        OutlinedTextField(
            value = form.serverUrl,
            onValueChange = { onChange(form.copy(serverUrl = it)) },
            placeholder = { Text(serverUrlPlaceholder(form.mode)) },
            isError = problem != null,
            supportingText = problem?.let { message -> { Text(message, modifier = Modifier.testTag(BackendSettingsTags.PROBLEM)) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(BackendSettingsTags.SERVER_URL),
        )
    }
}

/** What the server URL field suggests for [mode]. */
fun serverUrlPlaceholder(mode: LettaConfig.Mode): String = when (mode) {
    LettaConfig.Mode.SELF_HOSTED -> "iroh://<endpoint id>@host:port, iroh://<ticket> or https://…"
    LettaConfig.Mode.CLOUD -> "https://api.letta.com"
    LettaConfig.Mode.LOCAL -> "http://127.0.0.1:8283"
}

/** The mode's name as the selector shows it. */
val LettaConfig.Mode.settingsLabel: String
    get() = when (this) {
        LettaConfig.Mode.CLOUD -> "Cloud"
        LettaConfig.Mode.SELF_HOSTED -> "Self-hosted"
        LettaConfig.Mode.LOCAL -> "Local runtime"
    }

@Composable
private fun ModeSelector(mode: LettaConfig.Mode, onModeChange: (LettaConfig.Mode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        FieldLabel("Mode")
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            LettaConfig.Mode.entries.forEach { option ->
                FilterChip(selected = mode == option, onClick = { onModeChange(option) }, label = { Text(option.settingsLabel) })
            }
        }
    }
}

@Composable
private fun TokenField(hasSavedToken: Boolean, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        FieldLabel("Access token")
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(if (hasSavedToken) "Saved token hidden" else "Optional") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private class BackendSettingsButtonActions(
    val onSave: () -> Unit,
    val onClearToken: () -> Unit,
    val onResetIdentity: (() -> Unit)?,
)

@Composable
private fun BackendSettingsButtons(canSave: Boolean, hasSavedToken: Boolean, buttons: BackendSettingsButtonActions) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = buttons.onSave, enabled = canSave, modifier = Modifier.testTag(BackendSettingsTags.SAVE)) { Text("Save") }
        if (hasSavedToken) {
            OutlinedButton(onClick = buttons.onClearToken) { Text("Clear token") }
        }
        buttons.onResetIdentity?.let { reset ->
            OutlinedButton(onClick = reset) { Text("Reset Iroh identity") }
        }
    }
}
