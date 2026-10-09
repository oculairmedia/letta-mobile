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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.surface.composer.description
import com.letta.mobile.ui.chat.surface.composer.displayName
import com.letta.mobile.ui.theme.LettaDimens

/** The persisted default permission mode (letta-mobile-bzvro.13); the settings card is hidden where none is provided. */
internal val LocalDesktopPermissionModeSettings = compositionLocalOf<PermissionModeSettings?> { null }

/**
 * Settings card for the default permission mode: what a conversation runs in until its composer chip
 * picks another. It starts, and stays, on "Approve all" until changed; a change applies to runtimes
 * started afterwards.
 */
@Composable
internal fun DesktopPermissionModeSettingsCard() {
    val settings = LocalDesktopPermissionModeSettings.current ?: return
    val current by settings.defaultMode.collectAsState()
    var saveFailed by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Text("Default permission mode", style = MaterialTheme.typography.titleLarge)
            Text(
                "How tool calls are approved in conversations that have not started yet and have not picked a mode " +
                    "of their own. Running conversations keep the mode they started with; change one from the " +
                    "chip above the composer. Iroh connections ignore it: a connected node decides its own mode, " +
                    "and a direct Iroh App Server always runs as Approve all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                AppServerPermissionMode.entries.forEach { mode ->
                    DesktopRadioChip(selected = current == mode, onClick = { saveFailed = !settings.setDefaultMode(mode) }) {
                        DesktopControlText(mode.displayName())
                    }
                }
            }
            Text(
                current.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (saveFailed) {
                Text(
                    "Could not save the default; it is unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
