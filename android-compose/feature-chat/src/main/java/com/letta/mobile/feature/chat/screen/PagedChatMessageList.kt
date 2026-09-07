package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.letta.mobile.feature.chat.screen.messagelist.*
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.toChatRenderItemState
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes
import com.letta.mobile.ui.zoom.PinchScalePreviewController

internal fun residentTargetIndex(
    rows: List<com.letta.mobile.data.chat.projection.ChatRenderItem>,
    target: String,
    liveCount: Int,
    placeholdersBefore: Int,
): Int? = rows.indexOfFirst { it.containsMessageId(target) }
    .takeIf { it >= 0 }?.let { liveCount + placeholdersBefore + it }

/** Paging owns load hints, retries and dropping. Never materialize the settled snapshot. */
@Composable
internal fun PagedChatMessageList(
    presentation: ChatPagingPresentation,
    state: ChatUiState,
    callbacks: ChatContentCallbacks,
    appearance: ChatContentAppearance,
    modifier: Modifier = Modifier,
) {
    val pages = presentation.settled.collectAsLazyPagingItems()
    val live by presentation.live.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var targetPositioned by remember(presentation, appearance.scrollToMessageId) { mutableStateOf(false) }
    LaunchedEffect(presentation, appearance.scrollToMessageId, pages.itemSnapshotList, live.size) {
        val target = appearance.scrollToMessageId ?: return@LaunchedEffect
        if (!targetPositioned) {
            // The engine selects an around-target window. Inspect only resident rows;
            // never trigger sequential history loads to search for an absent target.
            val snapshot = pages.itemSnapshotList
            val index = residentTargetIndex(snapshot.items, target, live.size, snapshot.placeholdersBefore)
            if (index != null) {
                listState.scrollToItem(index)
                targetPositioned = true
            }
        }
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val dimens = MaterialTheme.chatDimens
    val shapes = MaterialTheme.chatShapes
    val geometry = remember(presentation) { ChatMessageGeometryState() }
    val pinch = remember { PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val context = ChatMessageListLazyContext(
            itemState = state.toChatRenderItemState(),
            conversationId = (state.conversationState as? com.letta.mobile.ui.chat.render.ConversationState.Ready)?.conversationId,
            chatMode = appearance.chatMode,
            contentWidthPx = with(density) { (maxWidth - dimens.contentPaddingHorizontal * 2).roundToPx() },
            density = density,
            layoutDirection = direction,
            activeFontScale = appearance.activeFontScale,
            liveFontScale = appearance.activeFontScale,
            newestMessageId = state.messages.lastOrNull()?.id,
            highlightedMessageId = appearance.scrollToMessageId,
            itemGeometryState = geometry,
            pinchFontScaleController = pinch,
            scaleWindowIndexRange = IntRange.EMPTY,
            callbacks = ChatMessageRenderCallbacks(
                callbacks.onSendMessage, callbacks.onRerunMessage, callbacks.onSubmitApproval,
                callbacks.onToggleRunCollapsed, callbacks.onToggleReasoningExpanded, callbacks.onAttachmentImageTap,
            ),
        )
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = dimens.contentPaddingHorizontal, end = dimens.contentPaddingHorizontal,
                top = appearance.topPadding, bottom = appearance.bottomPadding),
        ) {
            items(live.size, key = { live[it].key }) { index ->
                ChatMessageListRenderItem(ChatMessageListRenderItemParams(live[index], index, context, dimens, shapes))
            }
            items(pages.itemCount, key = pages.itemKey { it.key }) { index ->
                val row = pages[index]
                if (row != null) {
                    ChatMessageListRenderItem(ChatMessageListRenderItemParams(row, live.size + index, context, dimens, shapes))
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }
            val load = pages.loadState
            if (load.refresh is LoadState.Loading || load.append is LoadState.Loading) {
                item(key = "paging-loading") { CircularProgressIndicator() }
            }
            if (load.refresh is LoadState.Error || load.append is LoadState.Error || load.prepend is LoadState.Error) {
                item(key = "paging-retry") { TextButton(onClick = pages::retry) { Text("Retry history") } }
            }
        }
    }
}
