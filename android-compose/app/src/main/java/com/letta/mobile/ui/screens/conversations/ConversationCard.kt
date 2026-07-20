package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.TextInputDialog
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.util.formatRelativeTime

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationCard(
    display: ConversationDisplay,
    onClick: () -> Unit,
    onOpenAdmin: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onFork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversation = display.conversation
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    val title = conversation.summary?.takeIf { it.isNotBlank() } ?: "Conversation"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    HapticEffects.longPress(haptic)
                    showContextMenu = true
                },
            ),
        shape = RoundedCornerShape(12.dp),
        colors = LettaCardDefaults.listCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.listItemHeadline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val timeText = conversationActivityText(conversation)
            val metadataText = buildString {
                if (display.isPinned) {
                    append("Pinned • ")
                }
                append(display.agentName)
                if (timeText != null) {
                    append(" • ")
                    append(timeText)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = metadataText,
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = title,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_conversations_admin_details),
            icon = LettaIcons.ManageSearch,
            onClick = { showContextMenu = false; onOpenAdmin() },
        )
        ActionSheetItem(
            text = if (display.isPinned) "Unpin" else "Pin",
            icon = LettaIcons.Star,
            onClick = { showContextMenu = false; onTogglePinned() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_rename),
            icon = LettaIcons.Edit,
            onClick = { showContextMenu = false; showRenameDialog = true },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_fork),
            icon = LettaIcons.ForkRight,
            onClick = { showContextMenu = false; onFork() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
            onClick = { showContextMenu = false; showDeleteDialog = true },
            destructive = true,
        )
    }

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_conversations_dialog_delete_title),
        message = stringResource(R.string.screen_conversations_dialog_delete_confirm),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteDialog = false; onDelete() },
        onDismiss = { showDeleteDialog = false },
        destructive = true,
    )

    TextInputDialog(
        show = showRenameDialog,
        title = stringResource(R.string.screen_conversations_dialog_rename_title),
        label = stringResource(R.string.common_name),
        confirmText = stringResource(R.string.action_save),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showRenameDialog = false; onRename(it) },
        onDismiss = { showRenameDialog = false },
        initialValue = conversation.summary ?: "",
    )
}

@Composable
private fun conversationActivityText(conversation: com.letta.mobile.data.model.Conversation): String? {
    val timestamp = conversation.lastMessageAt ?: conversation.createdAt ?: return null
    val relative = formatRelativeTime(timestamp).takeIf { it.isNotBlank() } ?: return null
    return if (conversation.lastMessageAt != null) {
        stringResource(R.string.screen_conversations_last_activity_format, relative)
    } else {
        stringResource(R.string.screen_conversations_created_format, relative)
    }
}
