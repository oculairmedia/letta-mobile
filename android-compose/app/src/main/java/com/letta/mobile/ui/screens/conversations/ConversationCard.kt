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

data class ConversationCardCallbacks(
    val onClick: () -> Unit,
    val onOpenAdmin: () -> Unit,
    val onDelete: () -> Unit,
    val onRename: (String) -> Unit,
    val onTogglePinned: () -> Unit,
    val onFork: () -> Unit,
)

private data class ConversationCardMenuState(
    val show: Boolean,
    val title: String,
    val display: ConversationDisplay,
    val onDismiss: () -> Unit,
    val onRequestRename: () -> Unit,
    val onRequestDelete: () -> Unit,
)

private data class ConversationCardSurfaceParams(
    val title: String,
    val display: ConversationDisplay,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
    val modifier: Modifier = Modifier,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationCard(
    display: ConversationDisplay,
    callbacks: ConversationCardCallbacks,
    modifier: Modifier = Modifier,
) {
    val conversation = display.conversation
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val title = conversationCardTitle(display)

    ConversationCardSurface(
        params = ConversationCardSurfaceParams(
            title = title,
            display = display,
            onClick = callbacks.onClick,
            onLongClick = {
                HapticEffects.longPress(haptic)
                showContextMenu = true
            },
            modifier = modifier,
        ),
    )

    ConversationCardContextMenu(
        state = ConversationCardMenuState(
            show = showContextMenu,
            title = title,
            display = display,
            onDismiss = { showContextMenu = false },
            onRequestRename = { showRenameDialog = true },
            onRequestDelete = { showDeleteDialog = true },
        ),
        callbacks = callbacks,
    )

    ConversationCardConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_conversations_dialog_delete_title),
        message = stringResource(R.string.screen_conversations_dialog_delete_confirm),
        confirmText = stringResource(R.string.action_delete),
        destructive = true,
        onConfirm = callbacks.onDelete,
        onDismiss = { showDeleteDialog = false },
    )

    ConversationCardTextInputDialog(
        show = showRenameDialog,
        title = stringResource(R.string.screen_conversations_dialog_rename_title),
        label = stringResource(R.string.common_name),
        initialValue = conversation.summary ?: "",
        onConfirm = callbacks.onRename,
        onDismiss = { showRenameDialog = false },
    )
}

private fun conversationCardTitle(display: ConversationDisplay): String =
    display.conversation.summary?.takeIf { it.isNotBlank() } ?: "Conversation"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCardSurface(params: ConversationCardSurfaceParams) {
    Card(
        modifier = params.modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = params.onClick,
                onLongClick = params.onLongClick,
            ),
        shape = RoundedCornerShape(12.dp),
        colors = LettaCardDefaults.listCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = params.title,
                style = MaterialTheme.typography.listItemHeadline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = conversationCardMetadata(params.display),
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun conversationCardMetadata(display: ConversationDisplay): String {
    val timeText = conversationActivityText(display.conversation)
    return buildString {
        if (display.isPinned) {
            append("Pinned • ")
        }
        append(display.agentName)
        if (timeText != null) {
            append(" • ")
            append(timeText)
        }
    }
}

@Composable
private fun ConversationCardContextMenu(
    state: ConversationCardMenuState,
    callbacks: ConversationCardCallbacks,
) {
    ActionSheet(
        show = state.show,
        onDismiss = state.onDismiss,
        title = state.title,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_conversations_admin_details),
            icon = LettaIcons.ManageSearch,
            onClick = { state.onDismiss(); callbacks.onOpenAdmin() },
        )
        ActionSheetItem(
            text = conversationPinActionLabel(state.display.isPinned),
            icon = LettaIcons.Star,
            onClick = { state.onDismiss(); callbacks.onTogglePinned() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_rename),
            icon = LettaIcons.Edit,
            onClick = { state.onDismiss(); state.onRequestRename() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_fork),
            icon = LettaIcons.ForkRight,
            onClick = { state.onDismiss(); callbacks.onFork() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
            onClick = { state.onDismiss(); state.onRequestDelete() },
            destructive = true,
        )
    }
}

@Composable
private fun conversationPinActionLabel(isPinned: Boolean): String {
    return if (isPinned) "Unpin" else "Pin"
}

@Composable
private fun ConversationCardConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        show = show,
        title = title,
        message = message,
        confirmText = confirmText,
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { onDismiss(); onConfirm() },
        onDismiss = onDismiss,
        destructive = destructive,
    )
}

@Composable
private fun ConversationCardTextInputDialog(
    show: Boolean,
    title: String,
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    TextInputDialog(
        show = show,
        title = title,
        label = label,
        confirmText = stringResource(R.string.action_save),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { onDismiss(); onConfirm(it) },
        onDismiss = onDismiss,
        initialValue = initialValue,
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
