package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.systemcolor.isSystemInHighContrast
import dev.nucleusframework.systemcolor.systemAccentColor

@Composable
internal fun DesktopNucleusSettingsCard(
    state: DesktopNucleusState,
    actions: DestinationNucleusActions,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Desktop integration", style = MaterialTheme.typography.titleLarge)
            UpdateSettingsSection(state, actions)
            NotificationSettingsSection(state, actions)
            AutoLaunchSettingsSection(state, actions)
            AppearanceSettingsSection()
            SystemInfoSettingsSection(state.system, actions.onRefreshSystemInfo)
            Text(
                "Ctrl+Shift+Space opens the quick switcher globally. Closing the window keeps Letta running in the system tray.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateSettingsSection(state: DesktopNucleusState, actions: DestinationNucleusActions) {
    SettingsSection(
        title = "Updates",
        supporting = state.justUpdatedMessage ?: state.updateMessage
            ?: "GitHub releases, SHA-512 verification, and safe installer restart.",
    ) {
        if (state.updatePhase == DesktopUpdatePhase.Downloading) {
            LinearProgressIndicator(
                progress = { state.updateProgressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${state.updateProgressPercent}%", style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.updatePhase) {
                DesktopUpdatePhase.Available -> DesktopDefaultButton(onClick = actions.onDownloadUpdate) {
                    DesktopButtonContent("Download ${state.updateVersion.orEmpty()}")
                }
                DesktopUpdatePhase.ReadyToInstall -> DesktopDefaultButton(onClick = actions.onInstallUpdate) {
                    DesktopButtonContent("Install and restart")
                }
                DesktopUpdatePhase.Checking,
                DesktopUpdatePhase.Downloading,
                -> Unit
                else -> DesktopOutlinedButton(onClick = actions.onCheckForUpdates) {
                    DesktopButtonContent("Check for updates")
                }
            }
        }
    }
}

@Composable
private fun NotificationSettingsSection(state: DesktopNucleusState, actions: DestinationNucleusActions) {
    SettingsSection(
        title = "Notifications",
        supporting = state.notificationMessage ?: if (state.notificationsAvailable) {
            "Native notifications are available for completed responses and failures."
        } else {
            "The current desktop session could not initialize native notifications."
        },
    ) {
        DesktopOutlinedButton(
            onClick = actions.onTestNotification,
            enabled = state.notificationsAvailable,
        ) {
            DesktopButtonContent("Send test notification")
        }
    }
}

@Composable
private fun AutoLaunchSettingsSection(state: DesktopNucleusState, actions: DestinationNucleusActions) {
    val autoLaunchEnabled = state.autoLaunchState in setOf(
        AutoLaunchState.ENABLED,
        AutoLaunchState.ENABLED_BY_POLICY,
    )
    val externallyLocked = state.autoLaunchState in setOf(
        AutoLaunchState.DISABLED_BY_USER,
        AutoLaunchState.DISABLED_BY_POLICY,
        AutoLaunchState.ENABLED_BY_POLICY,
    )
    SettingsSection(
        title = "Start at login",
        supporting = "Nucleus backend: ${state.autoLaunchState.name.lowercase().replace('_', ' ')}",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Launch Letta when I sign in", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = autoLaunchEnabled,
                onCheckedChange = actions.onAutoLaunchChanged,
                enabled = state.autoLaunchState != AutoLaunchState.UNSUPPORTED && !externallyLocked,
            )
        }
        if (externallyLocked) {
            DesktopOutlinedButton(onClick = actions.onOpenAutoLaunchSettings) {
                DesktopButtonContent("Open system startup settings")
            }
        }
    }
}

@Composable
private fun AppearanceSettingsSection() {
    val dark = isSystemInDarkMode()
    val accent = systemAccentColor()
    val highContrast = isSystemInHighContrast()
    SettingsSection(
        title = "System appearance",
        supporting = "Reactive OS theme, accent, and accessibility contrast detection — no polling.",
    ) {
        MetricRow("Theme", if (dark) "Dark" else "Light")
        MetricRow("Accent", accent?.toHexLabel() ?: "Letta default")
        MetricRow("High contrast", if (highContrast) "On" else "Off")
    }
}

@Composable
private fun SystemInfoSettingsSection(system: DesktopSystemSnapshot, onRefresh: () -> Unit) {
    SettingsSection(
        title = "Runtime introspection",
        supporting = if (system.available) "Native host telemetry" else "Native system information unavailable",
    ) {
        MetricRow("CPU", system.cpuUsagePercent?.let { "${it.toInt()}% · ${system.physicalCores ?: "?"} cores" } ?: "—")
        MetricRow("Memory", formatMemory(system.memoryUsedBytes, system.memoryTotalBytes))
        MetricRow(
            "GPU",
            buildList {
                system.gpuName?.let(::add)
                system.gpuUsagePercent?.let { add("${it.toInt()}%") }
                system.gpuTemperatureCelsius?.let { add("${it.toInt()} °C") }
            }.joinToString(" · ").ifBlank { "—" },
        )
        MetricRow("CPU temperature", system.cpuTemperatureCelsius?.let { "${it.toInt()} °C" } ?: "—")
        MetricRow(
            "Network",
            "${if (system.connected == true) "Online" else "Offline"} · ↓${formatBytes(system.receivedBytes)} ↑${formatBytes(system.transmittedBytes)}",
        )
        DesktopOutlinedButton(onClick = onRefresh) { DesktopButtonContent("Refresh") }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    supporting: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatMemory(used: Long?, total: Long?): String {
    val usedBytes = used ?: return "—"
    val totalBytes = total?.takeIf { it > 0 } ?: return "—"
    return "${formatBytes(usedBytes)} / ${formatBytes(totalBytes)}"
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) "%.1f GiB".format(gib) else "${bytes / (1024 * 1024)} MiB"
}

private fun Color.toHexLabel(): String = "#%06X".format(value.toLong() and 0xFFFFFF)
