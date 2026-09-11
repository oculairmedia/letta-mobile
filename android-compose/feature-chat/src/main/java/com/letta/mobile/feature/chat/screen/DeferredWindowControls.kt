package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.coroutines.launch

/**
 * Reads a body too large to decode inline, one bounded page at a time.
 *
 * Each read returns a page of text and the offset the next one starts at, and only one page is
 * ever held: a page replaces its predecessor rather than appending, which is what keeps the read
 * bounded. Nothing is read until asked for.
 *
 * The page number is derived from the history depth rather than counted separately, so it cannot
 * drift out of step with the offsets it describes.
 */
@Composable
internal fun DeferredWindowControls(rowKey: String, read: suspend (Long) -> TimelineSemanticWindowResult) {
    val scope = rememberCoroutineScope()
    var text by remember(rowKey) { mutableStateOf<String?>(null) }
    var failure by remember(rowKey) { mutableStateOf<String?>(null) }
    var offset by remember(rowKey) { mutableStateOf(0L) }
    var next by remember(rowKey) { mutableStateOf<Long?>(null) }
    var page by remember(rowKey) { mutableStateOf(0) }
    val history = remember(rowKey) { java.util.ArrayDeque<Long>() }
    var busy by remember(rowKey) { mutableStateOf(false) }
    var job by remember(rowKey) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(rowKey) { onDispose { job?.cancel() } }

    fun load(at: Long, atPage: Int) {
        job?.cancel()
        job = scope.launch {
            busy = true
            text = null
            failure = null
            next = null
            offset = at
            page = atPage
            try {
                when (val result = read(at)) {
                    is TimelineSemanticWindowResult.Text -> {
                        text = result.value
                        next = result.nextScalarOffset
                    }
                    // Not a failure to retry: this body's shape is one the reader cannot window.
                    is TimelineSemanticWindowResult.Deferred -> text = "Content cannot be shown here (${result.reason})."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (thrown: Exception) {
                failure = thrown.message ?: "Could not load this page."
            } finally {
                busy = false
            }
        }
    }

    Column {
        text?.let { Text(it) }
        if (busy) Text("Loading…", style = MaterialTheme.typography.bodySmall)
        failure?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when {
            // Nothing read yet: one plain action, not a pager over an empty body.
            page == 0 && !busy -> TextButton(onClick = { load(0, 1) }) { Text("View content") }
            failure != null && !busy -> TextButton(onClick = { load(offset, page) }) { Text("Retry") }
            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    enabled = !busy && history.isNotEmpty(),
                    onClick = { load(history.removeLast(), page - 1) },
                ) { Icon(LettaIcons.ChevronLeft, contentDescription = "Previous page") }
                Text("Page $page", style = MaterialTheme.typography.bodySmall)
                IconButton(
                    enabled = !busy && next != null,
                    onClick = {
                        next?.let {
                            history.addLast(offset)
                            if (history.size > 128) history.removeFirst()
                            load(it, page + 1)
                        }
                    },
                ) { Icon(LettaIcons.ChevronRight, contentDescription = "Next page") }
            }
        }
    }
}
