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
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-bzvro.31: the transient "Chat archived - Undo" bar after a delete that only archived
 * the conversation. [pendingConversationId] is the conversation just archived (null shows nothing);
 * Undo calls [onUndo], while a timeout or dismissal calls [onExpire] and the deletion stands.
 */
@Composable
internal fun DesktopUndoDeleteSnackbar(
    pendingConversationId: String?,
    onUndo: (String) -> Unit,
    onExpire: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(pendingConversationId) {
        val id = pendingConversationId ?: return@LaunchedEffect
        val result = host.showSnackbar(
            message = UNDO_DELETE_MESSAGE,
            actionLabel = UNDO_DELETE_ACTION,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo(id) else onExpire(id)
    }
    SnackbarHost(
        hostState = host,
        modifier = modifier.padding(LettaDimens.Space.lg).testTag(UNDO_DELETE_TAG),
    )
}

internal const val UNDO_DELETE_MESSAGE = "Chat archived"
internal const val UNDO_DELETE_ACTION = "Undo"
internal const val UNDO_DELETE_TAG = "desktop-undo-delete-snackbar"
