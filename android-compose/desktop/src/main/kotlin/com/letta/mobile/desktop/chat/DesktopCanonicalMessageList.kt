package com.letta.mobile.desktop.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation

@Composable
internal fun DesktopCanonicalMessageList(presentation: CanonicalTimelinePresentation, modifier: Modifier = Modifier) {
    val settled = presentation.settled.collectAsLazyPagingItems()
    val live by presentation.live.collectAsState()
    LaunchedEffect(presentation, settled) {
        snapshotFlow { settled.itemSnapshotList.items }.collect { presentation.onResidentRows(it) }
    }
    val scope = rememberCoroutineScope()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var search by remember(presentation) { mutableStateOf("") }
    var missing by remember(presentation) { mutableStateOf(false) }
    val target by presentation.target.collectAsState()
    LaunchedEffect(presentation, target, settled.itemCount) {
        val restore = presentation.viewport
        val identity = restore?.first ?: target
        val index = settled.itemSnapshotList.items.indexOfFirst { it.identity.value == identity }
        if (index >= 0) list.scrollToItem(index + live.size, restore?.second ?: 0)
    }
    LaunchedEffect(presentation, list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving ->
            if (!moving && settled.itemCount > 0) settled.peek((list.firstVisibleItemIndex - live.size).coerceIn(0, settled.itemCount - 1))?.let {
                presentation.viewport = it.identity.value to list.firstVisibleItemScrollOffset
            }
        }
    }
    Column(modifier) {
        androidx.compose.material3.OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Message ID") })
        androidx.compose.material3.TextButton(onClick = { scope.launch { missing = !presentation.navigate(search) } }) { Text("Go to message") }
        androidx.compose.material3.TextButton(onClick = { scope.launch { presentation.navigate(null); list.scrollToItem(0) } }) { Text("Latest") }
        if (missing) Text("Message not found; keeping current history.")
        presentation.missingTarget?.let { Text("Message $it was not found. Showing recent history.") }
        val failure = listOf(settled.loadState.refresh, settled.loadState.append, settled.loadState.prepend)
            .filterIsInstance<androidx.paging.LoadState.Error>().firstOrNull()
        if (failure != null) {
            Text(failure.error.message ?: "History could not be loaded")
            androidx.compose.material3.TextButton(onClick = { settled.retry() }) { Text("Retry") }
        }
        if (listOf(settled.loadState.refresh, settled.loadState.append, settled.loadState.prepend)
                .any { it is androidx.paging.LoadState.Loading }) Text("Loading history...")
        LazyColumn(state = list, reverseLayout = true) {
            items(live, key = { "overlay-${it.key}" }) { CanonicalItem(it) }
            items(settled.itemCount, key = { settled.peek(it)?.item?.key ?: "loading-$it" }) { index ->
                settled[index]?.let { row ->
                    Column {
                        CanonicalItem(row.item)
                        if (row.deferred != null) DesktopDeferredWindow(presentation, row, kotlinx.coroutines.Dispatchers.Default)
                    }
                }
            }
        }
    }
}

@Composable
private fun CanonicalItem(item: ChatRenderItem) {
    when (item) {
        is ChatRenderItem.Single -> DesktopMessageBubble(item.message)
        is ChatRenderItem.RunBlock -> Column { item.messages.forEach { DesktopMessageBubble(it.first) } }
    }
}
