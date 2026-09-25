package com.letta.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** letta-mobile-1n5py: what the queued-sends panel can do; shared by the Android and desktop hosts. */
data class QueuedSendActions(
    val onCancel: (QueuedSendId) -> Unit = {},
    val onSendNow: (QueuedSendId) -> Unit = {},
    val onResume: () -> Unit = {},
)

/** Test tags for [QueuedSendsPanel]; row tags are suffixed with the message's id. */
object QueuedSendsPanelTestTags {
    const val PANEL = "queued_sends_panel"
    const val ROW = "queued_send_row_"
    const val CANCEL = "queued_send_cancel_"
    const val SEND_NOW = "queued_send_now_"
    const val RESUME = "queued_sends_resume"
}

/** The label on a queued message: where it stands in line. */
fun queuedSendLabel(position: Int): String = "Queued · $position"

/** The panel's header: how many wait, and whether a Stop is holding them. */
fun queuedSendsHeader(queue: ConversationSendQueue): String {
    val count = queue.items.size
    val noun = if (count == 1) "message" else "messages"
    return if (queue.paused) "Queue paused · $count $noun" else "$count $noun queued"
}

/**
 * letta-mobile-1n5py: the messages waiting behind the running turn, docked above the composer.
 *
 * Each row can be cancelled, or sent now (the running turn is stopped and this message runs
 * next). A queue held by a Stop offers Resume. Shared by the Android and desktop chat screens.
 */
@Composable
fun QueuedSendsPanel(
    queue: ConversationSendQueue,
    actions: QueuedSendActions,
    modifier: Modifier = Modifier,
) {
    if (queue.isEmpty) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(QueuedSendsPanelTestTags.PANEL),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs)) {
            QueuedSendsHeader(queue, actions.onResume)
            queue.items.forEachIndexed { index, item -> QueuedSendRow(item, position = index + 1, actions) }
        }
    }
}

@Composable
private fun QueuedSendsHeader(queue: ConversationSendQueue, onResume: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = queuedSendsHeader(queue),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (queue.paused) {
            TextButton(onClick = onResume, modifier = Modifier.testTag(QueuedSendsPanelTestTags.RESUME)) {
                Text("Resume")
            }
        }
    }
}

@Composable
private fun QueuedSendRow(
    item: QueuedChatSend,
    position: Int,
    actions: QueuedSendActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(QueuedSendsPanelTestTags.ROW + item.id.value),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = queuedSendLabel(position),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = item.previewText(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = { actions.onSendNow(item.id) },
            modifier = Modifier.testTag(QueuedSendsPanelTestTags.SEND_NOW + item.id.value),
        ) {
            Text("Send now")
        }
        IconButton(
            onClick = { actions.onCancel(item.id) },
            modifier = Modifier.testTag(QueuedSendsPanelTestTags.CANCEL + item.id.value),
        ) {
            Icon(LettaIcons.Close, contentDescription = "Cancel queued message")
        }
    }
}

private fun QueuedChatSend.previewText(): String = when {
    text.isNotBlank() -> text
    attachments.size == 1 -> "1 image"
    else -> "${attachments.size} images"
}
