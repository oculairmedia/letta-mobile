package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.ui.settings.BackendConnectionNotice
import com.letta.mobile.ui.settings.BackendConnectionNoticeActions

/** A conversation that has not opened: whether it is on its way, the connection, and why it failed. */
@Immutable
data class ShellChatWaiting(
    val connection: BackendConnectionStatus,
    val opening: Boolean = false,
    val error: String? = null,
)

/** The page in place of a conversation that has not opened: a spinner while it opens, else the connection notice. */
@Composable
fun ShellChatWaitingPage(waiting: ShellChatWaiting, actions: BackendConnectionNoticeActions, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (waiting.opening) {
            CircularProgressIndicator()
        } else {
            BackendConnectionNotice(
                status = waiting.connection,
                error = waiting.error ?: (waiting.connection as? BackendConnectionStatus.Failed)?.message,
                actions = actions,
            )
        }
    }
}
