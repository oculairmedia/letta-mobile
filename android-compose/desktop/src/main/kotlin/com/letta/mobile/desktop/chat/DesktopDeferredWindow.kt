package com.letta.mobile.desktop.chat

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.letta.mobile.data.timeline.*
import kotlinx.coroutines.launch

/** One page of a stored body, and the choice of which string is being paged. */
private class DesktopWindowState {
    var text by mutableStateOf<String?>(null)
    var offset by mutableStateOf(0L)
    var next by mutableStateOf<Long?>(null)
    var busy by mutableStateOf(false)
    var job by mutableStateOf<kotlinx.coroutines.Job?>(null)
    var chosenField: TimelineSemanticField? = null
    val history = java.util.ArrayDeque<Long>()
}

/**
 * Which string a stored body shows is discovered per row, never assumed to be `content`: on a tool
 * call that field holds `name(arguments)`, a serialization artifact (letta-mobile-jp78k).
 */
private suspend fun DesktopWindowState.page(
    read: suspend (TimelineSemanticField, Long) -> TimelineSemanticWindowResult,
    at: Long,
): TimelineSemanticWindowResult = chosenField?.let { read(it, at) } ?: resolveDeferredBody(read).let { body ->
    chosenField = body.field
    body.first ?: TimelineSemanticWindowResult.Deferred(
        body.reason ?: TimelineSemanticWindowResult.Reason.MissingField, body.messageType,
    )
}

/** A body that holds no text is an ordinary outcome, not an internal reason code. */
private fun TimelineSemanticWindowResult.Deferred.describe(): String =
    if (reason == TimelineSemanticWindowResult.Reason.MissingField) {
        "This record stores no text to show."
    } else {
        "Content remains deferred: $reason"
    }

/** Reads one page into [this], replacing whatever it held. Cancellation propagates untouched. */
private suspend fun DesktopWindowState.show(
    read: suspend (TimelineSemanticField, Long) -> TimelineSemanticWindowResult,
    at: Long,
) {
    busy = true; text = null; next = null; offset = at
    try {
        when (val result = page(read, at)) {
            is TimelineSemanticWindowResult.Text -> { text = result.value; next = result.nextScalarOffset }
            is TimelineSemanticWindowResult.Deferred -> text = result.describe()
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        text = failure.message ?: "Window unavailable; retry"
    } finally {
        busy = false
    }
}

@Composable
internal fun DesktopDeferredWindow(presentation: CanonicalTimelinePresentation, row: CanonicalTimelinePresentation.Row, dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    val state = remember(row) { DesktopWindowState() }
    DisposableEffect(presentation, row) { onDispose { state.job?.cancel() } }
    val read: suspend (TimelineSemanticField, Long) -> TimelineSemanticWindowResult =
        { field, from -> presentation.readTextWindow(row, field, from, dispatcher) }
    fun load(at: Long) {
        state.job?.cancel()
        state.job = scope.launch { state.show(read, at) }
    }
    androidx.compose.foundation.layout.Column {
        state.text?.let { Text(it) }
        if (state.busy) Text("Loading content window...")
        TextButton(onClick = { load(state.offset) }, enabled = !state.busy) { Text(if (state.text == null) "View content" else "Retry window") }
        TextButton(onClick = { load(if (state.history.isEmpty()) 0 else state.history.removeLast()) }, enabled = !state.busy && state.offset > 0) { Text("Previous window") }
        TextButton(onClick = { state.next?.let { state.history.addLast(state.offset); if (state.history.size > 128) state.history.removeFirst(); load(it) } }, enabled = !state.busy && state.next != null) { Text("Next window") }
    }
}
