package com.letta.mobile.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.runtime.supervisor.RuntimeHealth
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.ui.theme.LettaDimens
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher

/**
 * "Local runtime" settings card: the bundled letta-code child's health with Force restart
 * (letta-mobile-bzvro.3, F03) and its launch settings (letta-mobile-bzvro.5, F05).
 */
@Composable
internal fun DesktopLocalRuntimeSettingsCard(controller: DesktopLocalRuntimeController) {
    LaunchedEffect(controller) { controller.load() }
    val health by controller.health.collectAsState()
    val launch by controller.launch.collectAsState()
    val pickerLauncher = rememberDirectoryPickerLauncher(
        dialogSettings = FileKitDialogSettings(title = "Choose the runtime's default working directory"),
    ) { directory ->
        directory?.let { controller.changeWorkingDirectory(it.file.absolutePath) }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            Text("Local runtime", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            RuntimeHealthSection(health = health, onForceRestart = controller::forceRestart)
            RuntimeLaunchSection(
                launch = launch,
                onChangeDirectory = { pickerLauncher.launch() },
                onResetDirectory = controller::resetWorkingDirectory,
            )
        }
    }
}

@Composable
private fun RuntimeHealthSection(health: RuntimeHealth, onForceRestart: () -> Unit) {
    DesktopSettingsFieldLabel("Health")
    Text(
        text = health.describe(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (health.gaveUp || health.restartPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (health.recentStderr.isNotEmpty()) RuntimeStderrTail(health.recentStderr)
    DesktopOutlinedButton(onClick = onForceRestart) { DesktopButtonContent("Force restart") }
}

@Composable
private fun RuntimeStderrTail(lines: List<String>) {
    Text(
        text = lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(LettaDimens.Radius.sm))
            .padding(LettaDimens.Space.md),
    )
}

@Composable
private fun RuntimeLaunchSection(
    launch: DesktopRuntimeLaunchState,
    onChangeDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
) {
    DesktopSettingsFieldLabel("Default working directory")
    Text(launch.effectiveWorkingDirectory.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium)
    launch.message?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (launch.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        DesktopDefaultButton(onClick = onChangeDirectory) { DesktopButtonContent("Change…") }
        if (launch.workingDirectory != null) {
            DesktopOutlinedButton(onClick = onResetDirectory) { DesktopButtonContent("Use Documents") }
        }
    }
    Text(
        text = "Node heap ${launch.heapMb / 1024} GB (half of this machine's memory, at least 4 GB) · " +
            "instance ${launch.instanceId.take(8)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Chat-pane notice while the bundled runtime is down: "restarting" during backoff, and a Restart
 * action once automatic restarts gave up (F03). Shows nothing while the runtime is healthy.
 */
@Composable
internal fun DesktopLocalRuntimeBanner(modifier: Modifier = Modifier) {
    val health by DesktopLocalRuntimeHost.health.collectAsState()
    if (!health.restartPending && !health.needsForceRestart) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = LettaDimens.Space.xl, vertical = LettaDimens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(
            text = "Local runtime: ${health.describe()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        if (health.needsForceRestart) {
            DesktopDefaultButton(onClick = DesktopLocalRuntimeHost::forceRestart) { DesktopButtonContent("Restart") }
        }
    }
}
