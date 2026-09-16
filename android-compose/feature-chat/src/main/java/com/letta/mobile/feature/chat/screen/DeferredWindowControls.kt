package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.timeline.TimelineSemanticField
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import com.letta.mobile.data.timeline.resolveDeferredBody
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Reads one string of a stored body, chosen per row rather than assumed. */
internal typealias DeferredBodyRead = suspend (TimelineSemanticField, Long) -> TimelineSemanticWindowResult

/**
 * Reads a body too large to decode inline, one bounded page at a time.
 *
 * Each read returns a page of text and the offset the next one starts at, and only one page is
 * ever held: a page replaces its predecessor rather than appending, which is what keeps the read
 * bounded. Nothing is read until asked for.
 *
 * The first read also decides which string is being paged. A tool call keeps its text in one of
 * several places and never in `content`, which for a tool call holds `name(arguments)` - a
 * serialization artifact the normal projection blanks. Paging that put escaped argument JSON on
 * screen where the tool's output belonged (letta-mobile-jp78k).
 */
private class DeferredPageReader(
    private val scope: CoroutineScope,
    private val read: DeferredBodyRead,
) {
    var text by mutableStateOf<String?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /**
     * Derived from how many offsets are behind this one, never counted alongside them, so the
     * number shown cannot drift out of step with the page it describes.
     */
    var page by mutableStateOf(0)
        private set

    private var next by mutableStateOf<Long?>(null)
    private var offset = 0L
    private val history = ArrayDeque<Long>()
    private var job: Job? = null
    private var chosen: TimelineSemanticField? = null

    val started: Boolean get() = page > 0
    val canGoBack: Boolean get() = !busy && page > 1
    val canGoForward: Boolean get() = !busy && next != null

    fun first() = load(0L, 1)

    fun retry() = load(offset, page)

    fun back() {
        if (history.isEmpty()) return
        load(history.removeLast(), page - 1)
    }

    fun forward() {
        val ahead = next ?: return
        history.addLast(offset)
        if (history.size > 128) history.removeFirst()
        load(ahead, page + 1)
    }

    fun cancel() = job?.cancel()

    private fun load(at: Long, atPage: Int) {
        job?.cancel()
        job = scope.launch {
            busy = true
            text = null
            failure = null
            next = null
            offset = at
            page = atPage
            try {
                apply(chosen?.let { read(it, at) } ?: resolve())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Exception) {
                failure = thrown.message ?: "Could not load this page."
            } finally {
                busy = false
            }
        }
    }

    /** Runs once per row: find the string worth showing, and keep its first page. */
    private suspend fun resolve(): TimelineSemanticWindowResult {
        val body = resolveDeferredBody(read)
        chosen = body.field
        return body.first
            ?: TimelineSemanticWindowResult.Deferred(
                body.reason ?: TimelineSemanticWindowResult.Reason.MissingField,
                body.messageType,
            )
    }

    private fun apply(result: TimelineSemanticWindowResult) = when (result) {
        is TimelineSemanticWindowResult.Text -> {
            text = result.value
            next = result.nextScalarOffset
        }
        // Not a failure to retry: this body holds nothing this client can put on screen.
        is TimelineSemanticWindowResult.Deferred -> {
            text = if (result.reason == TimelineSemanticWindowResult.Reason.MissingField) {
                "This record stores no text to show."
            } else {
                "Content cannot be shown here (${result.reason})."
            }
        }
    }
}

@Composable
internal fun DeferredWindowControls(rowKey: String, read: DeferredBodyRead) {
    val scope = rememberCoroutineScope()
    val reader = remember(rowKey) { DeferredPageReader(scope, read) }
    DisposableEffect(rowKey) { onDispose { reader.cancel() } }
    Column {
        reader.text?.let { Text(it) }
        if (reader.busy) Text("Loading…", style = MaterialTheme.typography.bodySmall)
        reader.failure?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when {
            // Nothing read yet: one plain action, not a pager over an empty body.
            !reader.started && !reader.busy -> TextButton(onClick = reader::first) { Text("View content") }
            reader.failure != null && !reader.busy -> TextButton(onClick = reader::retry) { Text("Retry") }
            else -> DeferredPageBar(reader)
        }
    }
}

@Composable
private fun DeferredPageBar(reader: DeferredPageReader) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(enabled = reader.canGoBack, onClick = reader::back) {
            Icon(LettaIcons.ChevronLeft, contentDescription = "Previous page")
        }
        Text("Page ${reader.page}", style = MaterialTheme.typography.bodySmall)
        IconButton(enabled = reader.canGoForward, onClick = reader::forward) {
            Icon(LettaIcons.ChevronRight, contentDescription = "Next page")
        }
    }
}
