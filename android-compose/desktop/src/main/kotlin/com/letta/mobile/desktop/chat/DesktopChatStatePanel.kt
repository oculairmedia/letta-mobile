package com.letta.mobile.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.chat.runtime.isConnectionRetryable
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Readable measure for the status hero's centred body. Narrower than [ChatColumnMaxWidth] on
 * purpose: a 760dp line of centred text is hard to track.
 */
internal val ChatProseMaxWidth = 520.dp

@Composable
internal fun ChatStatePanel(
    state: DesktopChatSurfaceState,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenStatus = state.chatScreenStatus
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Same ground as the message list and the welcome pane (both paint
            // `background`): this hero sits over the ambient glow beside them,
            // and `surface` made the connect/error state a shade off its own pane.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LettaDimens.Orb.lg, vertical = LettaDimens.Space.xxl),
        contentAlignment = Alignment.Center,
    ) {
        val failureHeadline = failureHeadline(screenStatus, state.errorMessage)
        Column(
            modifier = Modifier.widthIn(max = ChatColumnMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            // The wordmark is a welcome, not a diagnosis. When the pane is here
            // because something BROKE, a display-size brand lockup on top pushes
            // the one line that says what happened into second place — so a
            // failure leads with its own headline and drops the wordmark.
            if (failureHeadline != null) {
                Text(
                    text = failureHeadline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = "LETTA DESKTOP",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = FontFamily.Serif,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 0.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = state.errorMessage ?: screenStatus.heroBody(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (failureHeadline != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = ChatProseMaxWidth),
            )
            if (screenStatus.isConnectionRetryable) {
                DesktopDefaultButton(
                    onClick = onRetryConnection,
                    enabled = !state.isLoading,
                ) {
                    DesktopButtonContent(
                        text = "Retry connection",
                        icon = Icons.Outlined.Refresh,
                    )
                }
            }
        }
    }
}


/**
 * The headline for a pane that is showing a FAILURE, or null when the pane is
 * simply idle/loading and the brand wordmark is the right thing to show.
 *
 * A carried [errorMessage] means something failed even when the status itself
 * reads as ordinary, so it counts as a failure regardless of [status].
 */
internal fun failureHeadline(status: ChatScreenStatus, errorMessage: String?): String? = when {
    status is ChatScreenStatus.BackendOffline -> "Can't reach the backend"
    status is ChatScreenStatus.SendFailed -> "Message wasn't sent"
    errorMessage != null -> "Something went wrong"
    else -> null
}

private fun ChatScreenStatus.heroBody(): String = when (this) {
    is ChatScreenStatus.ConfigNeeded -> "Configure a backend, then ask questions, inspect tools, and continue work across sessions."
    is ChatScreenStatus.BackendOffline -> "The configured backend could not be reached. Check the gateway, then retry the connection."
    is ChatScreenStatus.NoConversations -> "Ask a question, paste an error, or point me at a repo. I can read code, run tools, and help you ship."
    is ChatScreenStatus.Loading -> "Loading conversations from the configured Letta backend."
    is ChatScreenStatus.SendFailed -> "The last send failed. You can edit the message and try again."
    is ChatScreenStatus.Ready -> if (isSending) {
        "Sending your message to the active conversation."
    } else {
        "Connected to the configured backend."
    }
}
