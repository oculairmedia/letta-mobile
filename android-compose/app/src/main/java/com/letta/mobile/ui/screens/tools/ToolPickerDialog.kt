package com.letta.mobile.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.R
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.preview.LettaPreviewFrame

@Composable
fun ToolPickerDialog(
    tools: List<Tool>,
    selectedToolIds: List<String>,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var selection by remember(tools, selectedToolIds) { mutableStateOf(selectedToolIds.toSet()) }

    MultiFieldInputDialog(
        show = true,
        title = title,
        confirmText = stringResource(R.string.action_save),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selection.toList()) },
    ) {
        if (tools.isEmpty()) {
            Text(
                text = stringResource(R.string.screen_tools_empty_attached),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tools, key = { it.id.value }) { tool ->
                    TextButton(
                        onClick = {
                            selection = if (tool.id.value in selection) {
                                selection - tool.id.value
                            } else {
                                selection + tool.id.value
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = tool.id.value in selection,
                            onCheckedChange = null,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            tool.description?.let { description ->
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
            }
        }
    }
}

// region Previews

private fun previewPickerTool(
    name: String = "send_email",
    description: String? = "Send an email with a subject and body.",
) = Tool(
    id = com.letta.mobile.data.model.ToolId("tool-$name"),
    name = name,
    description = description,
    toolType = "composio",
    tags = listOf("email"),
)

@PreviewLightDark
@Composable
private fun ToolPickerDialogPreview() {
    LettaPreviewFrame {
        ToolPickerDialog(
            tools = listOf(
                previewPickerTool(),
                previewPickerTool(name = "fetch_url", description = "Fetch a URL and extract content."),
                previewPickerTool(name = "summarize_doc", description = "Summarize a long document."),
            ),
            selectedToolIds = listOf("tool-send_email"),
            title = "Attach tools",
            onDismiss = {},
            onConfirm = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun ToolPickerDialogEmptyPreview() {
    LettaPreviewFrame {
        ToolPickerDialog(
            tools = emptyList(),
            selectedToolIds = emptyList(),
            title = "Attach tools",
            onDismiss = {},
            onConfirm = {},
        )
    }
}

// endregion
