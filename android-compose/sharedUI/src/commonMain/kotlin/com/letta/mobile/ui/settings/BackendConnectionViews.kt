package com.letta.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

object BackendConnectionTags {
    const val STATUS = "backend-connection-status"
    const val NOTICE = "backend-connection-notice"
}

/** The status line's words. */
val BackendConnectionStatus.label: String
    get() = when (this) {
        BackendConnectionStatus.Unconfigured -> "Not configured"
        BackendConnectionStatus.Connecting -> "Connecting"
        is BackendConnectionStatus.Connected -> "Connected via $transport"
        is BackendConnectionStatus.Failed -> "Connection failed"
    }

/** What to do next, for a page that has no conversation to show yet. */
fun BackendConnectionStatus.hint(): String = when (this) {
    is BackendConnectionStatus.Connected -> "Pick an agent to start a conversation."
    BackendConnectionStatus.Connecting -> "Connecting to the App Server…"
    is BackendConnectionStatus.Failed -> "Could not reach the App Server. Check the backend settings."
    BackendConnectionStatus.Unconfigured -> "Configure a backend before starting a conversation."
}

@Composable
private fun BackendConnectionStatus.dotColor(): Color = when (this) {
    BackendConnectionStatus.Unconfigured -> MaterialTheme.customColors.offlineColor
    BackendConnectionStatus.Connecting -> MaterialTheme.customColors.reconnectingColor
    is BackendConnectionStatus.Connected -> MaterialTheme.customColors.onlineColor
    is BackendConnectionStatus.Failed -> MaterialTheme.colorScheme.error
}

/** A dot in the connection's colour and its [label]. */
@Composable
fun BackendConnectionStatusRow(status: BackendConnectionStatus, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.testTag(BackendConnectionTags.STATUS),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(StatusDotSize).background(status.dotColor(), CircleShape))
        Text(status.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Where the notice's buttons go; a null action leaves its button out. [onRetry] reconnects. */
data class BackendConnectionNoticeActions(
    val onOpenSettings: (() -> Unit)? = null,
    val onShowAgents: (() -> Unit)? = null,
    val onRetry: (() -> Unit)? = null,
)

/**
 * What a page shows before a conversation opens: the connection, what to do
 * next, the last [error], and the way to the settings or the agent list.
 */
@Composable
fun BackendConnectionNotice(
    status: BackendConnectionStatus,
    error: String?,
    actions: BackendConnectionNoticeActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = LettaDimens.Pane.noticeMaxWidth).padding(LettaDimens.Space.xl).testTag(BackendConnectionTags.NOTICE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        BackendConnectionStatusRow(status)
        Text(status.hint(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            actions.onRetry?.let { AssistChip(onClick = it, label = { Text("Retry") }) }
            actions.onShowAgents?.let { AssistChip(onClick = it, label = { Text("Agents") }) }
            actions.onOpenSettings?.let { AssistChip(onClick = it, label = { Text("Open settings") }) }
        }
    }
}

private val StatusDotSize = 8.dp
