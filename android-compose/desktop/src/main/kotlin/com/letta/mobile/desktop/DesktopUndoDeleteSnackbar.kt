package com.letta.mobile.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.desktop.chat.DesktopDeletionUndo
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-bzvro.31: the transient undo bar after a delete that did not destroy the chat. Its
 * wording follows what the delete did ([undoDeleteMessage]). [offer] is the delete just made (null
 * shows nothing); Undo calls [onUndo], while a timeout or dismissal calls [onExpire] and the
 * deletion stands. A newer offer replaces the bar of an older one.
 */
@Composable
internal fun DesktopUndoDeleteSnackbar(
    offer: DesktopDeletionUndo.Offer?,
    onUndo: (DesktopDeletionUndo.Offer) -> Unit,
    onExpire: (DesktopDeletionUndo.Offer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(offer) {
        val current = offer ?: return@LaunchedEffect
        val result = host.showSnackbar(
            message = undoDeleteMessage(current.behavior),
            actionLabel = UNDO_DELETE_ACTION,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo(current) else onExpire(current)
    }
    SnackbarHost(
        hostState = host,
        modifier = modifier.padding(LettaDimens.Space.lg).testTag(UNDO_DELETE_TAG),
    )
}

/** What the bar says: where the chat went, never a recovery the app cannot provide. */
internal fun undoDeleteMessage(behavior: ConversationDeleteBehavior): String = when (behavior) {
    ConversationDeleteBehavior.MovesToArchived -> "Moved to Archived"
    ConversationDeleteBehavior.RemovesFromLists -> "Removed from your chats"
    ConversationDeleteBehavior.Permanent -> "Chat deleted"
}

internal const val UNDO_DELETE_ACTION = "Undo"
internal const val UNDO_DELETE_TAG = "desktop-undo-delete-snackbar"
