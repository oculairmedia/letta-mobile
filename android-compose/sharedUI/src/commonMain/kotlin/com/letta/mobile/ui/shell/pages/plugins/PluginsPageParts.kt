package com.letta.mobile.ui.shell.pages.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.catalog.PickedPluginPackage
import com.letta.mobile.data.plugin.catalog.PluginId
import com.letta.mobile.data.plugin.catalog.PluginSummary
import com.letta.mobile.data.plugin.catalog.PluginsPageActions
import com.letta.mobile.data.plugin.catalog.PluginsPageState
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.host.LettaConfirmTags
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

@Composable
internal fun PluginsError(message: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(PluginsPageTags.ERROR), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(DISMISS) }
    }
}

/** This device's "Live plugin views" switch: plugin pages render live here, or as static cards. */
@Composable
internal fun LiveViewsRow(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(LIVE_VIEWS, style = MaterialTheme.typography.bodyLarge)
            Text(LIVE_VIEWS_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.testTag(PluginsPageTags.LIVE_VIEWS))
    }
}

/** "Install from file…", then the picked file's name and sha256 with Review / Discard. */
@Composable
internal fun InstallSection(state: PluginsPageState, actions: PluginsPageActions, onPickPackage: (() -> Unit)?) {
    val picked = state.picked
    if (picked != null) {
        PickedPackage(picked, state.busy, actions)
    } else if (onPickPackage != null) {
        PickButton(onPickPackage, state.busy)
    }
}

@Composable
private fun PickButton(onPickPackage: () -> Unit, busy: Boolean) {
    TextButton(onClick = onPickPackage, enabled = !busy, modifier = Modifier.testTag(PluginsPageTags.PICK)) {
        Icon(LettaIcons.FileOpen, contentDescription = null)
        Text(INSTALL_FROM_FILE, modifier = Modifier.padding(start = LettaDimens.Space.xs))
    }
}

@Composable
private fun PickedPackage(picked: PickedPluginPackage, busy: Boolean, actions: PluginsPageActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.sm).testTag(PluginsPageTags.PICKED)) {
        Text("${picked.fileName} (${picked.sizeBytes} bytes)", style = MaterialTheme.typography.bodyLarge)
        Text(
            "SHA-256 ${picked.sha256}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(PluginsPageTags.PICKED_SHA),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            TextButton(onClick = actions::discardPicked, enabled = !busy, modifier = Modifier.testTag(PluginsPageTags.DISCARD)) { Text(DISCARD) }
            TextButton(onClick = actions::uploadPicked, enabled = !busy, modifier = Modifier.testTag(PluginsPageTags.UPLOAD)) {
                Text(if (busy) UPLOADING else REVIEW)
            }
        }
    }
}

@Composable
internal fun PluginsList(state: PluginsPageState, actions: PluginsPageActions, modifier: Modifier) {
    RosterList(
        RosterSpec(
            sections = listOf(RosterSection(key = "plugins", items = state.plugins)),
            key = { it.id.value },
            options = RosterOptions(testTag = PluginsPageTags.LIST),
            empty = { LettaEmptyHint(EMPTY, Modifier.padding(LettaDimens.Space.md)) },
        ),
        modifier = modifier,
    ) { plugin, row ->
        RosterListRow(
            LettaListRowSpec(title = plugin.name, subtitle = plugin.subtitle, trailing = plugin.health.label),
            row,
            RosterRowSlots(end = { PluginRowEnd(plugin, state.busy, actions) }),
        )
    }
}

@Composable
private fun PluginRowEnd(plugin: PluginSummary, busy: Boolean, actions: PluginsPageActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        plugin.faultReason?.let { FaultBadge(plugin.id, it) }
        TextButton(onClick = { actions.checkHealth(plugin.id) }, enabled = !busy, modifier = Modifier.testTag(PluginsPageTags.health(plugin.id))) {
            Text(CHECK)
        }
        Switch(
            checked = plugin.enabled,
            onCheckedChange = { on -> actions.setEnabled(plugin.id, on) },
            enabled = !busy && plugin.state != PluginState.Updating,
            modifier = Modifier.testTag(PluginsPageTags.toggle(plugin.id)),
        )
        IconButton(onClick = { actions.askRemove(plugin.id) }, enabled = !busy, modifier = Modifier.testTag(PluginsPageTags.remove(plugin.id))) {
            Icon(LettaIcons.Delete, contentDescription = "Remove ${plugin.name}")
        }
    }
}

/** The plugin is faulted, or its health check failed: the reason, in the error colour. */
@Composable
private fun FaultBadge(id: PluginId, reason: String) {
    Text(
        FAULT,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(PluginsPageTags.fault(id)).semantics(mergeDescendants = true) { contentDescription = "$FAULT: $reason" },
    )
}

@Composable
internal fun DisableAllButton(state: PluginsPageState, actions: PluginsPageActions) {
    TextButton(
        onClick = { actions.askDisableAll(true) },
        enabled = state.anyEnabled && !state.busy,
        modifier = Modifier.padding(vertical = LettaDimens.Space.sm).testTag(PluginsPageTags.DISABLE_ALL),
    ) { Text(DISABLE_ALL, color = if (state.anyEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
internal fun DisableAllConfirm(actions: PluginsPageActions) {
    LettaConfirmPane(
        LettaConfirmRequest(
            title = DISABLE_ALL,
            message = "Every plugin stops now: its tools leave every agent and its elements show as static cards. You can enable each one again.",
            confirmLabel = DISABLE_ALL_CONFIRM,
            tags = LettaConfirmTags(confirm = PluginsPageTags.DISABLE_ALL_CONFIRM),
        ),
        onConfirm = actions::confirmDisableAll,
        onDismiss = { actions.askDisableAll(false) },
    )
}

@Composable
internal fun RemoveConfirm(name: String, actions: PluginsPageActions) {
    LettaConfirmPane(
        LettaConfirmRequest(
            title = "Remove $name?",
            message = "$name is uninstalled from the host. Its elements stay on boards as static cards.",
            confirmLabel = REMOVE,
            tags = LettaConfirmTags(confirm = PluginsPageTags.REMOVE_CONFIRM),
        ),
        onConfirm = actions::confirmRemove,
        onDismiss = { actions.askRemove(null) },
    )
}

/** The id and version, then why it is faulted or else its state. */
private val PluginSummary.subtitle: String
    get() = "$id · $version · " + (faultReason?.let { "$FAULT: $it" } ?: state.label)

private val PluginState.label: String
    get() = when (this) {
        PluginState.Installed -> "Off"
        PluginState.Enabled -> "On"
        PluginState.Active -> "Running"
        is PluginState.Faulted -> "Faulted"
        PluginState.Updating -> "Updating"
    }

private val PluginHealth.label: String
    get() = when (this) {
        PluginHealth.Unknown -> "Health unknown"
        PluginHealth.Ok -> "Healthy"
        is PluginHealth.Degraded -> "Degraded: $reason"
        is PluginHealth.Failed -> "Unhealthy"
    }

private const val DISMISS = "Dismiss"
private const val LIVE_VIEWS = "Live plugin views"
private const val LIVE_VIEWS_HELP = "On this device, plugin elements run their pages live. Off shows them as static cards."
private const val INSTALL_FROM_FILE = "Install from file…"
private const val DISCARD = "Discard"
private const val REVIEW = "Review and install"
private const val UPLOADING = "Uploading…"
private const val EMPTY = "No plugins installed"
private const val CHECK = "Check"
private const val FAULT = "Faulted"
private const val DISABLE_ALL = "Disable all plugins"
private const val DISABLE_ALL_CONFIRM = "Disable all"
private const val REMOVE = "Remove"
