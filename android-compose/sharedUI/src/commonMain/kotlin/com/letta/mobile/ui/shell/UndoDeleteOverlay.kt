package com.letta.mobile.ui.shell

import androidx.compose.runtime.Composable
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.OverlayWhile

/**
 *  (hard cut S13): the undo offer after a delete that did not
 * destroy the chat, through the window's [com.letta.mobile.ui.host.OverlayHost]. [offerKey] names the
 * delete just made (null shows nothing; a newer offer replaces the older one's bar). Undo calls
 * [onUndo]; a timeout or the dismiss control calls [onCommit] and the deletion stands. Replaces
 * desktop's DesktopUndoDeleteSnackbar.
 */
@Composable
fun UndoDeleteOverlay(
    offerKey: String?,
    behavior: ConversationDeleteBehavior?,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
) {
    val overlay = if (offerKey == null || behavior == null) {
        null
    } else {
        Overlay.undo(undoDeleteMessage(behavior), key = UNDO_DELETE_KEY, onUndo = onUndo, onCommit = onCommit)
    }
    OverlayWhile(overlay, key = offerKey)
}

/** What the bar says: where the chat went, never a recovery the app cannot provide. */
fun undoDeleteMessage(behavior: ConversationDeleteBehavior): String = when (behavior) {
    ConversationDeleteBehavior.MovesToArchived -> "Moved to Archived"
    ConversationDeleteBehavior.RemovesFromLists -> "Removed from your chats"
    ConversationDeleteBehavior.Permanent -> "Chat deleted"
}

private const val UNDO_DELETE_KEY = "undo-delete-conversation"
