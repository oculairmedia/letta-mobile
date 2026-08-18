package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import java.io.File

/**
 * Desktop settings card for the "local backend data directory" — where the
 * BUNDLED local letta-code runtime keeps its agents, conversations, and
 * provider credentials (`providers/auth.json`). Defaults to
 * `~/.letta-mobile/local-backend`, which is intentionally SEPARATE from the
 * `letta-code` CLI's own `~/.letta/lc-local-backend` — this card is how a
 * user with existing CLI agents points the desktop app at that same folder
 * instead of starting a fresh, empty one.
 *
 * Changing this setting swaps in a WHOLE separate set of live data (agents,
 * conversations) — this is not a neutral path field. Picking a new folder
 * therefore requires an explicit confirmation ([DesktopConfirmDialog]) that
 * spells out the consequence and, when the folder already has content,
 * tells the user that instead of silently adopting it.
 */
@Composable
internal fun DesktopLocalBackendDirectorySettingsCard(
    state: DesktopLocalBackendDirectoryState,
    actions: DesktopLocalBackendDirectoryActions,
) {
    LaunchedEffect(Unit) { actions.onLoad() }

    var pendingPath by remember { mutableStateOf<String?>(null) }

    val pickerLauncher = rememberDirectoryPickerLauncher(
        dialogSettings = FileKitDialogSettings(title = "Choose local backend data directory"),
    ) { directory ->
        directory?.let { pendingPath = it.file.absolutePath }
    }

    pendingPath?.let { candidate ->
        DesktopConfirmDialog(
            request = ConfirmDialogRequest(
                title = "Switch local backend data directory?",
                message = confirmSwitchMessage(candidate),
                confirmLabel = "Use this folder",
            ),
            onConfirm = {
                pendingPath = null
                actions.onChangeDirectory(candidate)
            },
            onDismiss = { pendingPath = null },
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DesktopLocalBackendDirectoryCardBody(
            state = state,
            onLaunchPicker = { pickerLauncher.launch() },
            onResetToDefault = actions.onResetToDefault,
        )
    }
}

@Composable
private fun DesktopLocalBackendDirectoryCardBody(
    state: DesktopLocalBackendDirectoryState,
    onLaunchPicker: () -> Unit,
    onResetToDefault: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Local backend data directory",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Where the bundled local Letta Code runtime keeps its agents, " +
                "conversations, and model provider credentials. This is separate from " +
                "the letta-code CLI's own data directory (~/.letta/lc-local-backend) — " +
                "point it there instead if you want this app to see agents you've " +
                "already created from a terminal. Switching this points the app at a " +
                "DIFFERENT set of live data, not just a folder rename.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DesktopLocalBackendDirectoryStatusLine(state)
        DesktopSettingsFieldLabel("Current directory")
        Text(
            text = state.effectivePath,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        DesktopLocalBackendDirectoryActionButtons(
            state = state,
            onLaunchPicker = onLaunchPicker,
            onResetToDefault = onResetToDefault,
        )
    }
}

@Composable
private fun DesktopLocalBackendDirectoryActionButtons(
    state: DesktopLocalBackendDirectoryState,
    onLaunchPicker: () -> Unit,
    onResetToDefault: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopDefaultButton(
            enabled = !state.isSaving,
            onClick = onLaunchPicker,
        ) {
            DesktopButtonContent(if (state.isSaving) "Saving…" else "Change…")
        }
        if (!state.isDefault) {
            DesktopDefaultButton(
                enabled = !state.isSaving,
                onClick = onResetToDefault,
            ) {
                DesktopButtonContent("Reset to default")
            }
        }
    }
}

@Composable
private fun DesktopLocalBackendDirectoryStatusLine(state: DesktopLocalBackendDirectoryState) {
    val message = state.message
    val color = when {
        message != null && state.isError -> MaterialTheme.colorScheme.error
        message != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = message ?: if (state.isDefault) {
        "Using the default location."
    } else {
        "Using a custom location."
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * Builds the confirmation message for switching to [candidatePath], calling
 * out whether the folder already holds data (so the user isn't surprised
 * that agents/conversations changed) versus is empty/new (so it's clear a
 * fresh, empty backend will be created there) — never a neutral "path
 * changed" message, per the consequence this setting actually has.
 *
 * A lightweight top-level-entry count only (no recursive size scan, to stay
 * cheap on a settings dialog) — enough to tell "has data" from "empty/new".
 */
private fun confirmSwitchMessage(candidatePath: String): String {
    val directory = File(candidatePath)
    val entryCount = runCatching { directory.list()?.size }.getOrNull()
    val contentsNote = when {
        !directory.exists() -> "This folder doesn't exist yet — it will be created empty, " +
            "and the bundled runtime will start with no agents."
        entryCount == null -> "Could not read this folder's contents."
        entryCount == 0 -> "This folder exists but is currently empty — the bundled runtime " +
            "will start with no agents."
        else -> "This folder already contains data ($entryCount item" +
            (if (entryCount == 1) "" else "s") + " at its top level) — the app will start " +
            "showing whatever agents and conversations live there instead of what you see now."
    }
    return "The bundled local runtime will restart against:\n\n$candidatePath\n\n" +
        "$contentsNote\n\n" +
        "If another Letta process (including the letta-code CLI) is already using this " +
        "folder, you may see a harmless \"could not claim scheduler lease\" notice in the " +
        "runtime log — only cron scheduling is affected, chat still works."
}
