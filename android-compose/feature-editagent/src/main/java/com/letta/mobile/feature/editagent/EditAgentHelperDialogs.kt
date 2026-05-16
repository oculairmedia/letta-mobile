package com.letta.mobile.feature.editagent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.editagent.R
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemoryBlockItem(
    block: EditAgentUiState.BlockState,
    onValueChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onLimitChange: (Int?) -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (block.readOnly) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showDeleteConfirm = true },
                )
        },
    ) {
        OutlinedTextField(
            value = block.value,
            onValueChange = onValueChange,
            label = { Text(block.label, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !block.readOnly,
            supportingText = block.limit?.let { limit ->
                { Text("${block.value.length}/$limit chars", style = MaterialTheme.typography.labelSmall) }
            },
            textStyle = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = block.description.orEmpty(),
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.common_description), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !block.readOnly,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = block.limit?.toString().orEmpty(),
            onValueChange = { value ->
                if (value.isBlank() || (value.toIntOrNull()?.let { it >= 0 } == true)) {
                    onLimitChange(value.toIntOrNull())
                }
            },
            label = { Text(stringResource(R.string.screen_agent_edit_character_limit), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !block.readOnly,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        if (block.isTemplate || block.readOnly) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (block.isTemplate) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.screen_agent_edit_block_template)) },
                    )
                }
                if (block.readOnly) {
                    AssistChip(
                        enabled = false,
                        onClick = {},
                        label = { Text(stringResource(R.string.screen_agent_edit_block_read_only)) },
                    )
                }
            }
        }
    }

    ConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.screen_agent_edit_detach_block_title, block.label),
        message = stringResource(R.string.screen_agent_edit_detach_block_message),
        confirmText = stringResource(R.string.action_remove),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteConfirm = false; onDelete() },
        onDismiss = { showDeleteConfirm = false },
        destructive = true,
    )
}

@Composable
internal fun AddBlockDialog(
    onDismiss: () -> Unit,
    onAdd: (label: String, value: String, description: String, limit: Int?) -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var newLimit by remember { mutableStateOf("") }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_agent_edit_add_memory_block),
        confirmText = stringResource(R.string.action_create),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = newLabel.isNotBlank(),
        onConfirm = { onAdd(newLabel, newValue, newDescription, newLimit.toIntOrNull()) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newLabel,
                onValueChange = { newLabel = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = { Text(stringResource(R.string.common_value)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newDescription,
                onValueChange = { newDescription = it },
                label = { Text(stringResource(R.string.common_description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = newLimit,
                onValueChange = { value ->
                    if (value.isBlank() || (value.toIntOrNull()?.let { it >= 0 } == true)) {
                        newLimit = value
                    }
                },
                label = { Text(stringResource(R.string.screen_agent_edit_character_limit)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
internal fun CloneAgentDialog(
    initialName: String,
    isCloning: Boolean,
    onDismiss: () -> Unit,
    onClone: (cloneName: String?, overrideExistingTools: Boolean, stripMessages: Boolean) -> Unit,
) {
    val defaultCloneName = if (initialName.isBlank()) ""
        else stringResource(R.string.screen_settings_clone_default_name_format, initialName)
    var cloneName by remember(defaultCloneName) { mutableStateOf(defaultCloneName) }
    var overrideExistingTools by remember { mutableStateOf(true) }
    var stripMessages by remember { mutableStateOf(true) }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_settings_clone_title),
        confirmText = stringResource(R.string.action_clone_agent),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = !isCloning,
        onConfirm = {
            onClone(cloneName.ifBlank { null }, overrideExistingTools, stripMessages)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.screen_settings_clone_dialog_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = cloneName,
                onValueChange = { cloneName = it },
                label = { Text(stringResource(R.string.screen_settings_clone_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.screen_agents_import_override_tools_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.screen_agents_import_override_tools_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = overrideExistingTools, onCheckedChange = { overrideExistingTools = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.screen_agents_import_strip_messages_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.screen_agents_import_strip_messages_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = stripMessages, onCheckedChange = { stripMessages = it })
            }
        }
    }
}

@Composable
internal fun ToolDetailDialog(
    tool: Tool,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = tool.name,
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LettaIcons.Tool, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(tool.name, style = MaterialTheme.typography.titleMedium)
            }
            tool.description?.let { desc ->
                Text(text = desc, style = MaterialTheme.typography.bodyMedium)
            }
            tool.toolType?.let { type ->
                Row {
                    Text(
                        text = stringResource(R.string.common_type) + ": ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = type, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (tool.tags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.common_tags) + ": " + tool.tags.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
