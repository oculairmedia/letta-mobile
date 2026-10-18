package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.host.LettaAlertPane
import com.letta.mobile.ui.host.LettaAlertSpec
import com.letta.mobile.ui.host.LettaPaneButton
import com.letta.mobile.ui.host.LettaPaneButtons
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Help overlay generated from [ShortcutRegistry] (letta-mobile-k5nbq.4): only the entries the
 * window's [ShellShortcutBinding] covers, with the chords of its platform (the web's alternates in
 * the browser).
 */
@Composable
fun ShortcutHelpOverlay(onDismiss: () -> Unit) {
    val binding = LocalShortcutHub.current.binding ?: return
    val platform = binding.platform
    val spec = LettaAlertSpec(
        title = "Keyboard shortcuts",
        buttons = LettaPaneButtons(dismiss = LettaPaneButton("Close", onDismiss)),
        kind = LettaPaneKind.Dialog,
        testTag = ShortcutHelpTags.PANE,
    )
    LettaAlertPane(spec, onDismiss) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
            ShortcutRegistry.grouped(binding::covers).forEach { (group, entries) ->
                item(key = group) { GroupTitle(group) }
                items(entries, key = { it.id }) { shortcut ->
                    ShortcutRow(shortcut, platform)
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(group: String) {
    Text(
        text = group,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ShortcutRow(shortcut: ShellShortcut, platform: ShortcutPlatform) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ShortcutHelpTags.ROW_PREFIX + shortcut.id.name),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(shortcut.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = shortcut.helpLabel(platform),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
