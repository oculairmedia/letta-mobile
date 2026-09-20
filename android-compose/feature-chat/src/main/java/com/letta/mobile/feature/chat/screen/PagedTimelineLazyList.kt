package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListLazyContext
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListRenderItem
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListRenderItemParams
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.mascot.MascotLoading
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes

internal class PagedTimelineLazyListParams(
    val pages: LazyPagingItems<ChatRenderItem>,
    val displayedLive: List<ChatRenderItem>,
    val presentation: ChatPagingPresentation,
    val listState: LazyListState,
    val kineticOverscroll: androidx.compose.foundation.OverscrollEffect?,
    val appearance: ChatContentAppearance,
    val context: ChatMessageListLazyContext,
    val agentId: String?,
)

@Composable
internal fun PagedTimelineLazyList(
    params: PagedTimelineLazyListParams,
    modifier: Modifier = Modifier,
) {
    val dimens = MaterialTheme.chatDimens
    LazyColumn(
        overscrollEffect = params.kineticOverscroll,
        modifier = modifier,
        state = params.listState,
        reverseLayout = true,
        contentPadding = PaddingValues(
            start = dimens.contentPaddingHorizontal,
            end = dimens.contentPaddingHorizontal,
            top = params.appearance.topPadding,
            bottom = params.appearance.bottomPadding,
        ),
    ) {
        val settledKey = params.pages.itemKey { it.key }
        items(
            count = params.displayedLive.size + params.pages.itemCount,
            key = { index ->
                params.displayedLive.getOrNull(index)?.key
                    ?: settledKey(index - params.displayedLive.size)
            },
        ) { index ->
            val liveRow = params.displayedLive.getOrNull(index)
            val pageIndex = index - params.displayedLive.size
            val row = liveRow ?: params.pages[pageIndex]
            if (row == null) {
                Spacer(Modifier.height(LettaDimens.Orb.railSlotWidth))
            } else {
                if (liveRow == null) {
                    params.presentation.deferredReader(row)?.let { reader -> DeferredWindowControls(row.key, reader) }
                }
                val older = if (liveRow != null) {
                    params.displayedLive.getOrNull(index + 1)
                        ?: if (params.pages.itemCount > 0) params.pages.peek(0) else null
                } else {
                    if (pageIndex + 1 < params.pages.itemCount) params.pages.peek(pageIndex + 1) else null
                }
                TimelineRow(row, index, older, params.context)
            }
        }
        val load = params.pages.loadState
        if (load.refresh is LoadState.Loading || load.append is LoadState.Loading) {
            item(key = "paging-loading") { MascotLoading(params.agentId) }
        }
        if (load.refresh is LoadState.Error || load.append is LoadState.Error || load.prepend is LoadState.Error) {
            item(key = "paging-retry") { TextButton(onClick = params.pages::retry) { Text("Retry history") } }
        }
    }
}

@Composable
internal fun TimelineRow(
    row: ChatRenderItem,
    index: Int,
    older: ChatRenderItem?,
    context: ChatMessageListLazyContext,
) {
    val dimens = MaterialTheme.chatDimens
    val shapes = MaterialTheme.chatShapes
    val rowKey = row.key
    val lifecycleObserver = LocalTimelineRowLifecycleObserver.current
    DisposableEffect(rowKey) {
        lifecycleObserver(TimelineRowLifecycle.Mount, rowKey)
        onDispose { lifecycleObserver(TimelineRowLifecycle.Dispose, rowKey) }
    }
    Column {
        PagedDateBoundary(row, older)
        ChatMessageListRenderItem(ChatMessageListRenderItemParams(row, index, context, dimens, shapes))
    }
}

@Composable
internal fun PagedDateBoundary(newer: ChatRenderItem, older: ChatRenderItem?) {
    pagedBoundaryDate(newer, older)?.let { DateSeparator(date = it) }
}

internal fun isInitialPageAvailable(
    pages: LazyPagingItems<ChatRenderItem>,
    refresh: LoadState,
): Boolean {
    val hasItems = pages.itemSnapshotList.items.isNotEmpty()
    val endReached = refresh is LoadState.NotLoading &&
        pages.loadState.source.prepend.endOfPaginationReached &&
        pages.loadState.source.append.endOfPaginationReached
    return hasItems || endReached
}

@Composable
internal fun PagedChatInitialLoadingView(
    refresh: LoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(modifier) {
        val label = if (refresh is LoadState.Error) "Could not load conversation" else "Loading conversation..."
        Text(label)
        if (refresh is LoadState.Error) {
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
