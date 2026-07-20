package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.ShimmerBox
import com.letta.mobile.ui.theme.dialogSectionHeading
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemMetadataMonospace
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.util.formatRelativeTime

internal data class ConversationAdminDialogState(
    val display: ConversationDisplay,
    val recompilePreview: String?,
    val inspectorMessages: List<ConversationInspectorMessage>,
    val isInspectorLoading: Boolean,
    val inspectorError: String?,
)

internal data class ConversationAdminDialogCallbacks(
    val onDismiss: () -> Unit,
    val onRename: (String) -> Unit,
    val onToggleArchived: (Boolean) -> Unit,
    val onFork: () -> Unit,
    val onCancelRuns: () -> Unit,
    val onRecompile: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
internal fun ConversationAdminDialog(
    state: ConversationAdminDialogState,
    callbacks: ConversationAdminDialogCallbacks,
) {
    var renameText by remember(state.display.conversation.id) {
        mutableStateOf(state.display.conversation.summary ?: "")
    }

    ConfirmDialog(
        show = true,
        title = stringResource(R.string.screen_conversations_admin_details),
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = callbacks.onDismiss,
        onDismiss = callbacks.onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConversationAdminDetailsSection(display = state.display)
            ConversationAdminRenameSection(
                renameText = renameText,
                onRenameTextChange = { renameText = it },
                onRename = callbacks.onRename,
            )
            ConversationAdminActionsSection(
                display = state.display,
                callbacks = callbacks,
            )
            ConversationAdminInspectorSection(
                isInspectorLoading = state.isInspectorLoading,
                inspectorError = state.inspectorError,
                inspectorMessages = state.inspectorMessages,
            )
            ConversationAdminRecompilePreviewSection(recompilePreview = state.recompilePreview)
        }
    }
}

@Composable
private fun ConversationAdminDetailsSection(display: ConversationDisplay) {
    val conversation = display.conversation
    CardGroup(title = { Text(stringResource(R.string.common_details)) }) {
        item(
            headlineContent = { Text(stringResource(R.string.common_id)) },
            supportingContent = {
                Text(conversation.id.value, style = MaterialTheme.typography.listItemMetadataMonospace)
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.common_agents)) },
            supportingContent = { Text(display.agentName, style = MaterialTheme.typography.listItemSupporting) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.common_status)) },
            supportingContent = {
                Text(
                    text = conversationArchivedStatusLabel(conversation.archived == true),
                    style = MaterialTheme.typography.listItemMetadata,
                )
            },
        )
        conversation.createdAt?.let {
            item(
                headlineContent = { Text(stringResource(R.string.common_created)) },
                supportingContent = {
                    Text(formatRelativeTime(it), style = MaterialTheme.typography.listItemMetadata)
                },
            )
        }
    }
}

@Composable
private fun conversationArchivedStatusLabel(isArchived: Boolean): String {
    return if (isArchived) {
        stringResource(R.string.screen_conversations_archived_label)
    } else {
        stringResource(R.string.screen_conversations_active_label)
    }
}

@Composable
private fun ConversationAdminRenameSection(
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onRename: (String) -> Unit,
) {
    CardGroup(title = { Text("Rename") }) {
        item(
            headlineContent = {
                FormItem(label = { Text(stringResource(R.string.common_name)) }) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = onRenameTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            trailingContent = {
                TextButton(onClick = { if (renameText.isNotBlank()) onRename(renameText) }) {
                    Text(stringResource(R.string.action_save))
                }
            },
        )
    }
}

@Composable
private fun ConversationAdminActionsSection(
    display: ConversationDisplay,
    callbacks: ConversationAdminDialogCallbacks,
) {
    val conversation = display.conversation
    val isArchived = conversation.archived == true
    CardGroup(title = { Text("Actions") }) {
        item(
            onClick = { callbacks.onToggleArchived(!isArchived) },
            headlineContent = { Text(conversationArchiveActionLabel(isArchived)) },
            leadingContent = {
                Icon(
                    imageVector = LettaIcons.Archive,
                    contentDescription = null,
                )
            },
        )
        item(
            onClick = callbacks.onFork,
            headlineContent = { Text(stringResource(R.string.action_fork)) },
            leadingContent = { Icon(imageVector = LettaIcons.ForkRight, contentDescription = null) },
        )
        item(
            onClick = callbacks.onCancelRuns,
            headlineContent = { Text(stringResource(R.string.screen_conversations_cancel_runs_action)) },
            leadingContent = { Icon(imageVector = LettaIcons.Close, contentDescription = null) },
        )
        item(
            onClick = callbacks.onRecompile,
            headlineContent = { Text(stringResource(R.string.screen_conversations_recompile_action)) },
            leadingContent = { Icon(imageVector = LettaIcons.Refresh, contentDescription = null) },
        )
        item(
            onClick = callbacks.onDelete,
            headlineContent = {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            },
            leadingContent = {
                Icon(
                    imageVector = LettaIcons.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
        )
    }
}

@Composable
private fun conversationArchiveActionLabel(isArchived: Boolean): String {
    return if (isArchived) {
        stringResource(R.string.screen_conversations_unarchive_action)
    } else {
        stringResource(R.string.screen_conversations_archive_action)
    }
}

@Composable
private fun ConversationAdminInspectorSection(
    isInspectorLoading: Boolean,
    inspectorError: String?,
    inspectorMessages: List<ConversationInspectorMessage>,
) {
    Text(
        text = stringResource(R.string.screen_conversations_message_inspector_title),
        style = MaterialTheme.typography.dialogSectionHeading,
    )
    when {
        isInspectorLoading -> ConversationAdminInspectorLoading()
        !inspectorError.isNullOrBlank() -> {
            Text(
                text = inspectorError,
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.error,
            )
        }
        inspectorMessages.isEmpty() -> {
            Text(
                text = stringResource(R.string.screen_conversations_message_inspector_empty),
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(inspectorMessages, key = { it.id }) { message ->
                    ConversationInspectorCard(message = message)
                }
            }
        }
    }
}

@Composable
private fun ConversationAdminInspectorLoading() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (i in 0..3) {
            ShimmerBox(height = 80.dp, widthFraction = 1f)
        }
    }
}

@Composable
private fun ConversationAdminRecompilePreviewSection(recompilePreview: String?) {
    if (recompilePreview.isNullOrBlank()) return
    Text(
        stringResource(R.string.screen_conversations_recompile_preview_title),
        style = MaterialTheme.typography.dialogSectionHeading,
    )
    Text(recompilePreview, style = MaterialTheme.typography.listItemSupporting)
}

@Composable
private fun ConversationInspectorCard(message: ConversationInspectorMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = message.messageType,
                    style = MaterialTheme.typography.listItemMetadata,
                )
                Text(
                    text = message.id,
                    style = MaterialTheme.typography.listItemMetadataMonospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = message.summary,
                style = MaterialTheme.typography.listItemSupporting,
            )
            message.detailLines.forEach { (label, value) ->
                Text(
                    text = "$label: $value",
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
