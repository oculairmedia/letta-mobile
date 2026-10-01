package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.paging.compose.collectAsLazyPagingItems
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.X
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_docked_reply_dismiss
import com.letta.mobile.sharedui.resources.chat_surface_docked_reply_expand
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.surface.timeline.rememberRowCallbacks
import com.letta.mobile.ui.chat.surface.timeline.rememberRowContexts
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRenderItemRow
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: the current exchange, floating above the docked chat bar so a prompt
 * sent from the canvas gets its reply on the canvas. It shows the newest turn (the latest
 * prompt and everything after it, approvals and tool cards included) with the same row
 * renderers as the full page, follows a streaming reply, and stays until dismissed or the
 * next prompt starts a new turn.
 */
@Immutable
internal class DockedReplyParams(
    val state: ChatUiState,
    val pagedTimeline: CanonicalTimelinePresentation?,
    val actions: ChatActions,
    val capabilities: ChatSurfaceCapabilities,
    val host: ChatSurfaceHost,
    val appearance: ChatSurfaceAppearance,
    val onIntent: (ChatSurfaceIntent) -> Unit,
    val maxHeight: Dp,
)

@Composable
internal fun DockedReplyCard(params: DockedReplyParams, modifier: Modifier = Modifier) {
    val turn = if (params.pagedTimeline != null) {
        rememberPagedTurn(params.pagedTimeline)
    } else {
        rememberMessageTurn(params.state, params.appearance)
    }
    var dismissedTurn by rememberSaveable { mutableStateOf<String?>(null) }
    val turnKey = turn.firstOrNull()?.key
    if (turn.isEmpty() || turnKey == dismissedTurn) return
    Surface(
        modifier = modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth().testTag(DOCKED_REPLY_TAG),
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = ChatSurfaceDimens.dockedReplyElevation,
        shadowElevation = ChatSurfaceDimens.dockedReplyElevation,
    ) {
        Column {
            DockedReplyHeader(
                agentName = params.state.agentName,
                onExpand = { params.onIntent(ChatSurfaceIntent.Expand) },
                onDismiss = { dismissedTurn = turnKey },
            )
            if (params.state.isStreaming) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.md))
            }
            DockedReplyBody(turn, params)
        }
    }
}

@Composable
private fun DockedReplyHeader(agentName: String, onExpand: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = LettaDimens.Space.md, end = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Text(
            text = agentName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onExpand, modifier = Modifier.size(LettaDimens.Control.iconButtonLg)) {
            Icon(
                Lucide.Maximize2,
                contentDescription = stringResource(Res.string.chat_surface_docked_reply_expand),
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(LettaDimens.Control.iconButtonLg)) {
            Icon(
                Lucide.X,
                contentDescription = stringResource(Res.string.chat_surface_docked_reply_dismiss),
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
    }
}

@Composable
private fun DockedReplyBody(turn: List<ChatRenderItem>, params: DockedReplyParams) {
    val scroll = rememberScrollState()
    // Follow the reply as it streams in, like the full page's follow-latest.
    LaunchedEffect(turn, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val contexts = rememberRowContexts(params.state, params.capabilities, params.appearance, rowFontScale(params.appearance))
    val callbacks = rememberRowCallbacks(params.actions, params.host) { _, _ -> params.onIntent(ChatSurfaceIntent.Expand) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = params.maxHeight)
            .verticalScroll(scroll)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        turn.forEach { item -> ChatRenderItemRow(item, contexts.forItem(item), callbacks) }
        Spacer(Modifier.size(LettaDimens.Space.xs))
    }
}

/** A host that scales text itself (desktop) leaves the rows at 1. */
private fun rowFontScale(appearance: ChatSurfaceAppearance): Float =
    if (appearance.fontScaleAppliedByHost) 1f else appearance.fontScale

@Composable
private fun rememberMessageTurn(state: ChatUiState, appearance: ChatSurfaceAppearance): List<ChatRenderItem> {
    val cache = remember(state.agentId) { IncrementalChatRenderItemsCache() }
    return remember(state.messages, appearance.displayMode) {
        currentTurn(cache.renderItems(state.messages, appearance.displayMode, state.messageListChange, state.agentId))
    }
}

/** The paged route: live rows (the turn in flight) over the newest settled rows. */
@Composable
private fun rememberPagedTurn(presentation: CanonicalTimelinePresentation): List<ChatRenderItem> {
    val live by presentation.live.collectAsState()
    val settled = presentation.settled.collectAsLazyPagingItems()
    val snapshot = settled.itemSnapshotList
    return remember(live, snapshot) {
        val liveKeys = live.mapTo(HashSet()) { it.key }
        val newestSettled = snapshot.items.take(TURN_LOOKBACK).map { it.item }.filter { it.key !in liveKeys }
        currentTurn(live + newestSettled)
    }
}

/**
 * The newest turn, oldest first: from the latest user prompt to the newest item. [newestFirst]
 * is the timeline's render order (newest at index 0).
 */
internal fun currentTurn(newestFirst: List<ChatRenderItem>): List<ChatRenderItem> {
    if (newestFirst.isEmpty()) return emptyList()
    val prompt = newestFirst.indexOfFirst { it is ChatRenderItem.Single && it.message.role == "user" }
    val turn = if (prompt < 0) newestFirst.take(TURN_LOOKBACK) else newestFirst.subList(0, prompt + 1)
    return turn.asReversed()
}

internal const val DOCKED_REPLY_TAG = "chat-docked-reply"
private const val TURN_LOOKBACK = 12
