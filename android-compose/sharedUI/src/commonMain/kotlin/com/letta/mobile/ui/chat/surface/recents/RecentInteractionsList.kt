package com.letta.mobile.ui.chat.surface.recents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_recents_empty
import com.letta.mobile.sharedui.resources.chat_surface_recents_new_thread
import com.letta.mobile.sharedui.resources.chat_surface_recents_row
import com.letta.mobile.sharedui.resources.chat_surface_recents_row_archived
import com.letta.mobile.sharedui.resources.chat_surface_recents_row_current
import com.letta.mobile.sharedui.resources.chat_surface_recents_title
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import com.letta.mobile.ui.theme.ChatHeadDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-y5q9z: the agent's recent interactions, inside the canvas bubble's card (phone) or
 * the docked panel (desktop): a "New thread" action over this agent's conversations, the current
 * one marked and the archived (dismissed) ones muted. Picking one hops to it; the bubble stays
 * where it is.
 */

/** What picking a row or "New thread" does: the host's action, then the list closes. */
internal class RecentInteractionsActions(
    val openConversation: (String) -> Unit,
    val newThread: () -> Unit,
)

/**
 * The list itself. It scrolls past [maxHeight]; with nothing to list it says so under the "New
 * thread" action, which is always there.
 */
@Composable
internal fun RecentInteractionsList(
    rows: List<ShellConversationRowModel>,
    actions: RecentInteractionsActions,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(RECENTS_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        RecentsHeader(onNewThread = actions.newThread)
        if (rows.isEmpty()) {
            RecentsEmpty()
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                items(rows, key = { it.id }) { row -> RecentInteractionRow(row) { actions.openConversation(row.id) } }
            }
        }
    }
}

/** "Recent interactions" with the "New thread" action at its end. */
@Composable
private fun RecentsHeader(onNewThread: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            stringResource(Res.string.chat_surface_recents_title),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FilledTonalButton(
            onClick = onNewThread,
            modifier = Modifier.heightIn(min = ChatHeadDimens.plus).testTag(RECENTS_NEW_THREAD_TAG),
        ) {
            Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.padding(end = LettaDimens.Space.xs))
            Text(stringResource(Res.string.chat_surface_recents_new_thread))
        }
    }
}

@Composable
private fun RecentsEmpty() {
    Text(
        stringResource(Res.string.chat_surface_recents_empty),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md)
            .testTag(RECENTS_EMPTY_TAG),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * One conversation: the agent's avatar, its title and how long ago it moved. The current one is
 * bold and selected; an archived one is muted. One announcement for the whole row.
 */
@Composable
private fun RecentInteractionRow(row: ShellConversationRowModel, onOpen: () -> Unit) {
    val label = recentRowLabel(row)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatHeadDimens.recentsRowMinHeight)
            .testTag(recentRowTag(row.id))
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = label
                selected = row.selected
                role = Role.Button
                onClick { onOpen(); true }
            }
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
            .then(if (row.archived) Modifier.alpha(LettaDimens.Alpha.disabled) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Box { AgentSphere(size = ChatHeadDimens.recentsAvatar) }
        Text(
            row.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (row.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            row.timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun recentRowLabel(row: ShellConversationRowModel): String = when {
    row.selected -> stringResource(Res.string.chat_surface_recents_row_current, row.title, row.timeLabel)
    row.archived -> stringResource(Res.string.chat_surface_recents_row_archived, row.title, row.timeLabel)
    else -> stringResource(Res.string.chat_surface_recents_row, row.title, row.timeLabel)
}

internal fun recentRowTag(id: String): String = "$RECENTS_ROW_TAG_PREFIX$id"

internal const val RECENTS_LIST_TAG = "chat-recents-list"
internal const val RECENTS_NEW_THREAD_TAG = "chat-recents-new-thread"
internal const val RECENTS_EMPTY_TAG = "chat-recents-empty"
internal const val RECENTS_ROW_TAG_PREFIX = "chat-recents-row-"
