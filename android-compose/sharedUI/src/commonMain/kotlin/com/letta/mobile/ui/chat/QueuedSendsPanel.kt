package com.letta.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.chat.send.ServerQueueBlocker
import com.letta.mobile.ui.components.disclosure.LettaMenuIndicator
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaChip
import com.letta.mobile.ui.components.foundation.LettaChipSlots
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

private val QUEUED_ON_SERVER_DISPLAY_DELAY = 1.seconds

/**
 * What the queued-message row can do; shared by the Android and desktop hosts. [onEdit] takes a
 * queued message back into the composer (null where the host cannot, which disables Edit).
 */
data class QueuedSendActions(
    val onCancel: (QueuedSendId) -> Unit = {},
    val onSendNow: (QueuedSendId) -> Unit = {},
    val onResume: () -> Unit = {},
    val onEdit: ((QueuedChatSend) -> Unit)? = null,
)

/** Test tags for [QueuedSendsPanel]; per-message tags are suffixed with the message's id. */
internal object QueuedSendsPanelTestTags {
    const val PANEL = "queued_sends_panel"
    const val ROW = "queued_send_row_"
    const val CANCEL = "queued_send_cancel_"
    const val SEND_NOW = "queued_send_now_"
    const val EDIT = "queued_send_edit_"
    const val MORE = "queued_sends_more"
    const val RESUME = "queued_sends_resume"
    const val ON_SERVER = "queued_send_on_server"
}

/** The label on a queued message: where it stands in line. */
fun queuedSendLabel(position: Int): String = "Queued · $position"

/** The server queue notice does not identify which client owns the preceding response. */
const val QUEUED_ON_SERVER_LABEL = "Queued on server · waiting for the previous response"

/** The label when it waits for this device's own stopped turn to wind down. */
const val QUEUED_BEHIND_OWN_STOP_LABEL = "Queued on server · waiting for the previous response to stop"

/** The label on [ConversationSendQueue.queuedOnServer]: what it waits behind. */
fun queuedOnServerLabel(behind: ServerQueueBlocker): String = when (behind) {
    ServerQueueBlocker.OtherClient -> QUEUED_ON_SERVER_LABEL
    ServerQueueBlocker.OwnStoppingTurn -> QUEUED_BEHIND_OWN_STOP_LABEL
    ServerQueueBlocker.OwnRunningTurn -> QUEUED_ON_SERVER_LABEL
}

/** The row's header: how many wait, and what holds them (a Stop, or another device's turn). */
fun queuedSendsHeader(queue: ConversationSendQueue): String {
    val count = queue.items.size + if (queue.queuedOnServer != null) 1 else 0
    val noun = if (count == 1) "message" else "messages"
    return when {
        queue.paused -> "Queue paused · $count $noun"
        queue.heldByOtherClient -> "Waiting for another device · $count $noun"
        else -> "$count $noun queued"
    }
}

/**
 * The messages waiting behind the running turn, as one compact row docked just above the prompt
 * bar: "1 message queued" (or how many, and what holds them) with the next message's first line,
 * dimmed, then three quiet buttons for it: send it now (the running turn stops and it runs next),
 * edit it (back into the composer, out of the queue), remove it. More than one waiting adds a "+N"
 * that opens the rest, each with the same buttons; a queue held by a Stop offers Resume. A message
 * already parked on the server shows with what it waits behind, without buttons. Shared by the
 * Android and desktop chat screens.
 */
@Composable
fun QueuedSendsPanel(
    queue: ConversationSendQueue,
    actions: QueuedSendActions,
    modifier: Modifier = Modifier,
) {
    val showOnServer = rememberQueuedOnServerVisible(queue.queuedOnServer?.id)
    val displayed = if (showOnServer) queue else queue.copy(queuedOnServer = null)
    if (displayed.isEmpty) return
    var expanded by remember { mutableStateOf(false) }
    val next = displayed.items.firstOrNull()
    val rest = displayed.items.drop(1)
    val more = rest.size + if (next != null && displayed.queuedOnServer != null) 1 else 0
    Surface(
        modifier = modifier.fillMaxWidth().testTag(QueuedSendsPanelTestTags.PANEL),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(
                start = LettaDimens.Space.md,
                end = LettaDimens.Space.xs,
                top = LettaDimens.Space.xs,
                bottom = LettaDimens.Space.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            SummaryRow(displayed, next, actions) {
                if (more > 0) MoreToggle(more, expanded) { expanded = !expanded }
            }
            if (next == null) displayed.queuedOnServer?.let { OnServerLine(it, displayed.queuedOnServerBehind) }
            if (expanded && more > 0) {
                rest.forEachIndexed { index, item -> QueuedSendRow(item, position = index + 2, actions) }
                displayed.queuedOnServer?.let { OnServerLine(it, displayed.queuedOnServerBehind) }
            }
        }
    }
}

@Composable
private fun rememberQueuedOnServerVisible(id: QueuedSendId?): Boolean {
    var visible by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id != null) {
            delay(QUEUED_ON_SERVER_DISPLAY_DELAY)
            visible = true
        }
    }
    return visible
}

/**
 * The row: the header in bold, the [next] message's first line dimmed (a parked one shows under it), then
 * [trailing] (the "+N"), Resume when the queue is paused, and the next message's buttons.
 */
@Composable
private fun SummaryRow(
    queue: ConversationSendQueue,
    next: QueuedChatSend?,
    actions: QueuedSendActions,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().then(next?.let { Modifier.testTag(QueuedSendsPanelTestTags.ROW + it.id.value) } ?: Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = queuedSendsHeader(queue),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = next?.previewText().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
        if (queue.paused) {
            LettaChip(text = "Resume", onClick = actions.onResume, modifier = Modifier.testTag(QueuedSendsPanelTestTags.RESUME))
        }
        next?.let { QueuedSendButtons(it, actions) }
    }
}

/** "+2" and the menu mark: opens the rest of the queue under the row. */
@Composable
private fun MoreToggle(more: Int, expanded: Boolean, onToggle: () -> Unit) {
    val state = if (expanded) "Expanded" else "Collapsed"
    LettaChip(
        text = "+$more",
        onClick = onToggle,
        modifier = Modifier.testTag(QueuedSendsPanelTestTags.MORE).semantics(mergeDescendants = true) {
            contentDescription = "$more more queued"
            stateDescription = state
        },
        slots = LettaChipSlots(trailing = { LettaMenuIndicator() }),
    )
}

/** One more queued message under the row: its place in line, its first line, its buttons. */
@Composable
private fun QueuedSendRow(item: QueuedChatSend, position: Int, actions: QueuedSendActions) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(QueuedSendsPanelTestTags.ROW + item.id.value),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = queuedSendLabel(position),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = item.previewText(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        QueuedSendButtons(item, actions)
    }
}

/** Send now, Edit and Remove for [item], as quiet icon buttons. */
@Composable
private fun QueuedSendButtons(item: QueuedChatSend, actions: QueuedSendActions) {
    val id = item.id.value
    QuietButton(LettaIcons.SendNow, "Send queued message now", QueuedSendsPanelTestTags.SEND_NOW + id) {
        actions.onSendNow(item.id)
    }
    val edit = actions.onEdit
    QuietButton(LettaIcons.Edit, "Edit queued message", QueuedSendsPanelTestTags.EDIT + id, enabled = edit != null) {
        edit?.invoke(item)
    }
    QuietButton(LettaIcons.Close, "Remove queued message", QueuedSendsPanelTestTags.CANCEL + id) {
        actions.onCancel(item.id)
    }
}

@Composable
private fun QuietButton(
    glyph: ImageVector,
    label: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    LettaIconButton(
        icon = LettaButtonIcon(
            imageVector = glyph,
            contentDescription = label,
            size = LettaControlTokens.iconButton,
            glyph = LettaIconStyle(LettaControlTokens.icon, MaterialTheme.colorScheme.onSurfaceVariant),
        ),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
    )
}

/** Already sent; the server runs it once the turn ahead of it ends. */
@Composable
private fun OnServerLine(item: QueuedChatSend, behind: ServerQueueBlocker) {
    Column(modifier = Modifier.fillMaxWidth().testTag(QueuedSendsPanelTestTags.ON_SERVER)) {
        Text(
            text = queuedOnServerLabel(behind),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = item.previewText(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun QueuedChatSend.previewText(): String = when {
    text.isNotBlank() -> text.lineSequence().first { it.isNotBlank() }
    attachments.size == 1 -> "1 image"
    else -> "${attachments.size} images"
}
