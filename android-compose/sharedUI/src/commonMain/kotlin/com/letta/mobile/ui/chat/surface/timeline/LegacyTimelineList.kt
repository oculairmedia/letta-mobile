package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.launch

/** Everything the legacy list reads. */
@Immutable
internal class LegacyTimelineParams(
    val state: ChatUiState,
    val actions: ChatActions,
    val capabilities: ChatSurfaceCapabilities,
    val appearance: ChatSurfaceAppearance,
    val bindings: TimelineRowBindings,
    val bottomReserve: Dp,
    /** False while the agent's mascot shows its thinking beside the composer. */
    val showThinkingRow: Boolean = true,
)

private const val THINKING_KEY = "__thinking__"
private const val LOADING_OLDER_KEY = "__loading_older__"

/**
 * letta-mobile-bglj6.1: the timeline over `ChatUiState.messages`, for owners without a canonical
 * paged timeline. Lifted from desktop's MessageList (tool folding, day dividers, follow-latest)
 * and Android's ChatMessageList (reversed layout, older-history window, render-item cache).
 */
@Composable
internal fun LegacyTimelineList(params: LegacyTimelineParams, modifier: Modifier = Modifier) {
    val state = params.state
    val cache = remember { IncrementalChatRenderItemsCache() }
    val items = remember(state.messages, state.messageListChange, params.appearance.displayMode, state.agentId) {
        cache.renderItems(state.messages, params.appearance.displayMode, state.messageListChange, state.agentId)
    }
    val rows = remember(items) { timelineRowsNewestFirst(items) }
    val conversationId = (state.conversationState as? ConversationState.Ready)?.conversationId
    val listState = remember(conversationId) { LazyListState() }
    val thinking = state.isAgentTyping && params.showThinkingRow
    val leading = if (thinking) 1 else 0

    val follow = rememberLegacyFollow(listState, conversationId, rows, thinking)
    OlderHistoryEffect(
        listState = listState,
        gate = OlderHistoryGate(
            hasMoreOlder = state.hasMoreOlderMessages,
            isLoadingOlder = state.isLoadingOlderMessages,
            residentMessages = state.messages.size,
            canRelease = params.capabilities.pagedHistory,
        ),
        onLoadOlder = params.actions::loadOlderMessages,
        onReleaseOlder = params.actions::releaseOlderMessages,
    )
    // One midnight watcher for the whole list, not one per divider.
    val today = rememberCurrentDate()
    val pinned by rememberPinnedPrompt(listState, leading + rows.size) { index ->
        (rows.getOrNull(index - leading) as? TimelineRow.Item)?.item
    }
    TimelineListFrame(
        listState = listState,
        bindings = params.bindings,
        overlays = TimelineFrameOverlays(
            pinnedPrompt = pinned,
            showScrollToLatest = follow.showScrollToLatest,
            onScrollToLatest = follow.scrollToLatest,
            bottomReserve = params.bottomReserve,
        ),
        modifier = modifier,
    ) {
        legacyRows(rows, params.bindings, thinking, state.isLoadingOlderMessages, today, state.agentId)
    }
}

/**
 * Newest-first rows for the reversed list. Folding and day sections are computed in chat order
 * (where "the first row of a day" means what it says) and then flipped.
 */
internal fun timelineRowsNewestFirst(newestFirstItems: List<ChatRenderItem>): List<TimelineRow> =
    withDayDividers(groupToolCallRows(newestFirstItems.asReversed())).asReversed()

private fun LazyListScope.legacyRows(
    rows: List<TimelineRow>,
    bindings: TimelineRowBindings,
    thinking: Boolean,
    loadingOlder: Boolean,
    today: LocalDate,
    agentId: String?,
) {
    if (thinking) item(key = THINKING_KEY) { ThinkingRow() }
    items(count = rows.size, key = { rows[it].key }, contentType = { rows[it]::class.simpleName }) { index ->
        TimelineRowContent(rows[index], bindings, today)
    }
    if (loadingOlder) {
        item(key = LOADING_OLDER_KEY) { TimelineOlderHistoryLoading(agentId) }
    }
}

@Composable
private fun TimelineRowContent(row: TimelineRow, bindings: TimelineRowBindings, today: LocalDate) {
    when (row) {
        is TimelineRow.Item -> TimelineItemRow(row.item, bindings)
        is TimelineRow.ToolGroup -> ToolGroupRow(row, bindings.contexts, bindings.callbacks)
        is TimelineRow.DayDivider -> DayDividerRow(row.date, today)
    }
}

/** The follow-latest state the frame needs. */
internal class TimelineFollow(
    val showScrollToLatest: Boolean,
    val scrollToLatest: () -> Unit,
)

/**
 * Follow-latest (desktop MessageListFollowEffects over the shared ChatViewportFollowPolicy):
 * leaving the newest edge stops following and shows the button; a tail change while following
 * snaps back; a prompt the user just sent always brings them to it.
 */
@Composable
private fun rememberLegacyFollow(
    listState: LazyListState,
    conversationId: String?,
    rows: List<TimelineRow>,
    thinking: Boolean,
): TimelineFollow {
    val scope = rememberCoroutineScope()
    var following by remember(conversationId) { mutableStateOf(true) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val dragged by rememberUpdatedState(isDragged)
    LaunchedEffect(listState) {
        snapshotFlow { listState.reversedViewportSnapshot(dragged) }
            .distinctUntilChanged()
            .collect { following = ChatViewportFollowPolicy.nextFollowModeAfterScroll(following, it) }
    }
    val newest = rows.firstOrNull { it !is TimelineRow.DayDivider }
    FollowTailEffect(listState, newest, rows.size, thinking) { following }
    ForceFollowOnSendEffect(listState, newest) { following = true }
    val showButton = ChatViewportFollowPolicy.shouldShowScrollToLatest(listState.reversedViewportSnapshot(isDragged))
    return TimelineFollow(showScrollToLatest = showButton) {
        following = true
        scope.launch { listState.animateScrollToItem(0) }
    }
}

@Composable
private fun FollowTailEffect(
    listState: LazyListState,
    newest: TimelineRow?,
    rowCount: Int,
    thinking: Boolean,
    following: () -> Boolean,
) {
    val tailLength = (newest as? TimelineRow.Item)?.item?.contentLength() ?: 0
    LaunchedEffect(newest?.key, rowCount, tailLength, thinking) {
        if (ChatViewportFollowPolicy.shouldAutoFollow(following(), rowCount) && !listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }
}

/** A NEW user prompt at the tail is the user's own send: always land on it (Android). */
@Composable
private fun ForceFollowOnSendEffect(listState: LazyListState, newest: TimelineRow?, onFollow: () -> Unit) {
    val previousKey = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(newest?.key) {
        val key = newest?.key
        val isNewPrompt = newest?.isUserPrompt() == true && previousKey.value != null && key != previousKey.value
        previousKey.value = key
        if (isNewPrompt) {
            onFollow()
            listState.animateScrollToItem(0)
        }
    }
}

private fun ChatRenderItem.contentLength(): Int = when (this) {
    is ChatRenderItem.Single -> message.content.length
    is ChatRenderItem.RunBlock -> messages.sumOf { it.first.content.length }
}
