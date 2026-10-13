package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.chat.runtime.isConnectionRetryable
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for [HomeConnectionPanel]. */
object HomeConnectionPanelTags {
    const val PANEL = "home_connection_panel"
    const val RETRY = "home_connection_retry"
}

/** What [HomeConnectionPanel] shows: the connection's status and any error it carries. */
data class HomeConnectionState(
    val status: ChatScreenStatus,
    val errorMessage: String? = null,
    /** A connection attempt is running: Retry waits for it. */
    val loading: Boolean = false,
)

/**
 * letta-mobile-ckmub.3.11: the hero the Home page shows until the backend is there (connecting,
 * no backend configured, offline, or a failed connect), with Retry when retrying can help. Moved
 * from desktop's ChatStatePanel when the legacy desktop chat surface was deleted.
 *
 * A failure leads with its own headline (see [connectionFailureHeadline]) instead of the
 * [wordmark]: the one line that says what happened must not sit under a brand lockup.
 */
@Composable
fun HomeConnectionPanel(
    state: HomeConnectionState,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
    wordmark: String = "LETTA",
) {
    val headline = connectionFailureHeadline(state.status, state.errorMessage)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LettaDimens.Orb.lg, vertical = LettaDimens.Space.xxl)
            .testTag(HomeConnectionPanelTags.PANEL),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = ChatColumnMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            ConnectionHeadline(headline, wordmark)
            Text(
                text = state.errorMessage ?: connectionHeroBody(state.status),
                style = MaterialTheme.typography.bodyLarge,
                color = if (headline != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = ConnectionProseMaxWidth),
            )
            if (state.status.isConnectionRetryable) {
                Button(
                    onClick = onRetryConnection,
                    enabled = !state.loading,
                    modifier = Modifier.testTag(HomeConnectionPanelTags.RETRY),
                ) {
                    // A call to action that redials the backend, not a list reload: no refresh glyph.
                    Text("Retry connection")
                }
            }
        }
    }
}

@Composable
private fun ConnectionHeadline(headline: String?, wordmark: String) {
    if (headline != null) {
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    } else {
        Text(
            text = wordmark,
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
}

/**
 * The headline for a panel that shows a FAILURE, or null when the connection is simply idle or
 * loading and the wordmark is the right thing to show. A carried [errorMessage] means something
 * failed even when the status itself reads as ordinary.
 */
fun connectionFailureHeadline(status: ChatScreenStatus, errorMessage: String?): String? = when {
    // F02: the connect preflight classified the server; these do not fix themselves by waiting.
    errorMessage?.startsWith(AUTHENTICATION_FAILED_PREFIX) == true -> "The server rejected the access token"
    errorMessage?.startsWith(INCOMPATIBLE_SERVER_PREFIX) == true -> "This server is not a compatible App Server"
    status is ChatScreenStatus.BackendOffline -> "Can't reach the backend"
    status is ChatScreenStatus.SendFailed -> "Message wasn't sent"
    errorMessage != null -> "Something went wrong"
    else -> null
}

// Prefixes of AppServerProbeResult.summary() for the terminal kinds.
private const val AUTHENTICATION_FAILED_PREFIX = "Authentication failed:"
private const val INCOMPATIBLE_SERVER_PREFIX = "Incompatible server:"

/** Readable measure for the panel's centred prose (narrower than the chat column on purpose). */
private val ConnectionProseMaxWidth = 520.dp

internal fun connectionHeroBody(status: ChatScreenStatus): String = when (status) {
    is ChatScreenStatus.ConfigNeeded -> "Configure a backend, then ask questions, inspect tools, and continue work across sessions."
    is ChatScreenStatus.BackendOffline -> "The configured backend could not be reached. Check the gateway, then retry the connection."
    is ChatScreenStatus.NoConversations -> "Ask a question, paste an error, or point me at a repo. I can read code, run tools, and help you ship."
    is ChatScreenStatus.Loading -> "Loading conversations from the configured Letta backend."
    is ChatScreenStatus.SendFailed -> "The last send failed. You can edit the message and try again."
    is ChatScreenStatus.Ready -> if (status.isSending) {
        "Sending your message to the active conversation."
    } else {
        "Connected to the configured backend."
    }
}
