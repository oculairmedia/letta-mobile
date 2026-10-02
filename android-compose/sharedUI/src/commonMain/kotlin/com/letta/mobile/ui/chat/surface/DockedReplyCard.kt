package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_dock_empty
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.surface.timeline.ObserveResidentRows
import com.letta.mobile.ui.chat.surface.timeline.rememberRowCallbacks
import com.letta.mobile.ui.chat.surface.timeline.rememberRowContexts
import com.letta.mobile.ui.chat.surface.timeline.rememberTimelineFadeAlphas
import com.letta.mobile.ui.chat.surface.timeline.timelineFadingEdges
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRenderItemRow
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: the conversation inside the docked panel, so a prompt sent from the
 * canvas gets its reply on the canvas. Newest at the bottom, drawn with the same row renderers
 * as the full page: the current turn (approvals and tool cards included) when the panel is
 * short, and as much earlier history as fits when it is taller. It follows a streaming reply
 * while the person is at the bottom.
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
)

@Composable
internal fun DockedReplyCard(params: DockedReplyParams, modifier: Modifier = Modifier) {
    val newestFirst = rememberDockedHistory(params)
    if (newestFirst.isEmpty()) {
        Box(modifier.testTag(DOCKED_REPLY_TAG), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.chat_surface_dock_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(LettaDimens.Space.lg),
            )
        }
        return
    }
    val listState = rememberLazyListState()
    // Follow the newest item while the person is reading the bottom of the conversation.
    val newestKey = newestFirst.first().key
    LaunchedEffect(newestKey) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    val contexts = rememberRowContexts(params.state, params.capabilities, params.appearance, rowFontScale(params.appearance))
    val callbacks = rememberRowCallbacks(params.actions, params.host) { _, _ -> params.onIntent(ChatSurfaceIntent.Expand) }
    // The full page's edge fades (TimelineListFrame), on the same reversed-list terms: the top
    // dissolves under the panel's header while older rows are above, the bottom above the composer
    // only while newer ones are below, so the newest message at rest is never dimmed.
    val fades = rememberTimelineFadeAlphas(
        canScrollTowardOlder = listState.canScrollForward,
        canScrollTowardNewer = listState.canScrollBackward,
        promptPinned = false,
    )
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier
            .clipToBounds()
            .timelineFadingEdges(fades)
            .testTag(DOCKED_REPLY_TAG),
        contentPadding = PaddingValues(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md, Alignment.Bottom),
    ) {
        items(newestFirst, key = { it.key }) { item -> ChatRenderItemRow(item, contexts.forItem(item), callbacks) }
    }
}

/** The conversation's newest items, newest first: the paged route's, or the message list's. */
@Composable
internal fun rememberDockedHistory(params: DockedReplyParams): List<ChatRenderItem> =
    if (params.pagedTimeline != null) {
        rememberPagedHistory(params.pagedTimeline)
    } else {
        rememberMessageHistory(params.state, params.appearance)
    }

/** A host that scales text itself (desktop) leaves the rows at 1. */
private fun rowFontScale(appearance: ChatSurfaceAppearance): Float =
    if (appearance.fontScaleAppliedByHost) 1f else appearance.fontScale

@Composable
private fun rememberMessageHistory(state: ChatUiState, appearance: ChatSurfaceAppearance): List<ChatRenderItem> {
    val cache = remember(state.agentId) { IncrementalChatRenderItemsCache() }
    return remember(state.messages, appearance.displayMode) {
        cache.renderItems(state.messages, appearance.displayMode, state.messageListChange, state.agentId).take(HISTORY_LIMIT)
    }
}

/** The paged route: live rows (the turn in flight) over the newest settled rows. */
@Composable
private fun rememberPagedHistory(presentation: CanonicalTimelinePresentation): List<ChatRenderItem> {
    val live by presentation.live.collectAsState()
    val settled = presentation.settled.collectAsLazyPagingItems()
    // Docked is the default view: without this the live overlay would never drain while docked.
    ObserveResidentRows(presentation, settled)
    val snapshot = settled.itemSnapshotList
    return remember(live, snapshot) {
        val liveKeys = live.mapTo(HashSet()) { it.key }
        val newestSettled = snapshot.items.take(HISTORY_LIMIT).map { it.item }.filter { it.key !in liveKeys }
        (live + newestSettled).take(HISTORY_LIMIT)
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

/** How far back the docked panel's history reaches; the full page has the rest. */
private const val HISTORY_LIMIT = 60
