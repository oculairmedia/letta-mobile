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

@Composable
internal fun DesktopDeferredWindow(presentation: CanonicalTimelinePresentation, row: CanonicalTimelinePresentation.Row, dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    val state = remember(row) { DesktopWindowState() }
    DisposableEffect(presentation, row) { onDispose { state.job?.cancel() } }
    fun load(at: Long) {
        state.job?.cancel()
        state.job = scope.launch {
            state.busy = true; state.text = null; state.next = null; state.offset = at
            try {
                val result = state.page({ field, from -> presentation.readTextWindow(row, field, from, dispatcher) }, at)
                when (result) {
                    is TimelineSemanticWindowResult.Text -> { state.text = result.value; state.next = result.nextScalarOffset }
                    // A body that holds no text is an ordinary outcome, not an internal reason code.
                    is TimelineSemanticWindowResult.Deferred -> state.text =
                        if (result.reason == TimelineSemanticWindowResult.Reason.MissingField) {
                            "This record stores no text to show."
                        } else {
                            "Content remains deferred: ${result.reason}"
                        }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { state.text = failure.message ?: "Window unavailable; retry" }
            finally { state.busy = false }
        }
    }
    androidx.compose.foundation.layout.Column {
        state.text?.let { Text(it) }
        if (state.busy) Text("Loading content window...")
        TextButton(onClick = { load(state.offset) }, enabled = !state.busy) { Text(if (state.text == null) "View content" else "Retry window") }
        TextButton(onClick = { load(if (state.history.isEmpty()) 0 else state.history.removeLast()) }, enabled = !state.busy && state.offset > 0) { Text("Previous window") }
        TextButton(onClick = { state.next?.let { state.history.addLast(state.offset); if (state.history.size > 128) state.history.removeFirst(); load(it) } }, enabled = !state.busy && state.next != null) { Text("Next window") }
    }
}
