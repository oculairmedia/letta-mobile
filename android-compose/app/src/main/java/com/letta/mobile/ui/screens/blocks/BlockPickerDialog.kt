package com.letta.mobile.ui.screens.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.R
import com.letta.mobile.data.model.Block
import com.letta.mobile.ui.common.UiState

@Composable
fun BlockPickerDialog(
    excludedBlockIds: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    viewModel: BlockLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selection by remember(excludedBlockIds) { mutableStateOf(emptySet<String>()) }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_agent_edit_attach_existing_block),
        confirmText = stringResource(R.string.action_attach),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = selection.isNotEmpty(),
        onConfirm = { onConfirm(selection.toList()) },
    ) {
        when (val state = uiState) {
            is UiState.Loading -> {
                Text(stringResource(R.string.common_loading))
            }
            is UiState.Error -> {
                Text(state.message)
            }
            is UiState.Success -> {
                val availableBlocks = state.data.blocks.filter { it.id !in excludedBlockIds }
                if (availableBlocks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_blocks_empty_available),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(availableBlocks, key = { it.id }) { block ->
                            BlockPickerRow(
                                block = block,
                                selected = block.id in selection,
                                onToggle = {
                                    selection = if (block.id in selection) {
                                        selection - block.id
                                    } else {
                                        selection + block.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockPickerRow(
    block: Block,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = null,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = block.label ?: stringResource(R.string.common_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                block.isTemplate?.takeIf { it }?.let {
                    Text(
                        text = " • ${stringResource(R.string.screen_agent_edit_block_template)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            block.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
