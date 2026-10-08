package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.transport.appserver.AppServerToolset
import com.letta.mobile.ui.theme.LettaDimens

/** Small pieces the model-control surfaces share (letta-mobile-w4q4p). */

/** One line under the progress bar: an error in the error colour, else a confirmation. */
@Composable
internal fun ModelControlNotice(error: String?, message: String?) {
    val text = error ?: message ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
    )
}

@Composable
internal fun DisconnectConfirmDialog(provider: ConnectableProvider, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect ${provider.displayName}?") },
        text = { Text("Its models disappear from every app's model picker until you connect it again.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Reasoning-effort chooser for a model that advertises variants. "Default"
 * restores the provider default (`reasoning_effort: null`).
 */
@Composable
fun ReasoningEffortChips(
    efforts: List<String>,
    onSelect: (effort: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (efforts.isEmpty()) return
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        AssistChip(onClick = { onSelect(null) }, label = { Text("Default") })
        efforts.forEach { effort ->
            AssistChip(
                onClick = { onSelect(effort) },
                label = { Text(effort) },
                modifier = Modifier.testTag("reasoning_effort_$effort"),
            )
        }
    }
}

/**
 * Toolset chooser (letta-mobile-bzvro.22): lets the user switch the active toolset
 * when the server advertises available toolsets.
 */
@Composable
fun ToolsetSelectorRow(
    toolsets: List<AppServerToolset>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (toolsets.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
            .testTag("toolset_selector_row"),
    ) {
        Text(
            text = "Toolset",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = LettaDimens.Space.xs),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            toolsets.forEach { toolset ->
                val isSelected = toolset.id == selectedId || (selectedId == null && toolset.id == "auto")
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(toolset.id) },
                    label = { Text(toolset.displayName ?: toolset.label ?: toolset.id) },
                    modifier = Modifier.testTag("toolset_chip_${toolset.id}"),
                )
            }
        }
    }
}

/** Test tags of the connect dialog. */
object ProviderPaneTags {
    const val SUBMIT = "provider_connect_submit"
}

/** Test tags of [ProviderManagementPane]. Prefixes are followed by the provider key or model handle. */
object ProviderManagementTags {
    const val PANE = "provider_management_pane"
    const val SEARCH = "provider_management_search"
    const val REFRESH = "provider_management_refresh"
    const val FILTER_PREFIX = "provider_filter_"
    const val PROVIDER_PREFIX = "provider_row_"
    const val CONNECT_PREFIX = "provider_connect_"
    const val DISCONNECT_PREFIX = "provider_disconnect_"
    const val SHOW_ALL_PREFIX = "provider_show_all_"
    const val HIDE_ALL_PREFIX = "provider_hide_all_"
    const val MODEL_PREFIX = "model_row_"
    const val MODEL_SWITCH_PREFIX = "model_switch_"
}
