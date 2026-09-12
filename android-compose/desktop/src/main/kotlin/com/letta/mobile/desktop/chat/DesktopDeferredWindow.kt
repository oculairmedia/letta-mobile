package com.letta.mobile.desktop.chat

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.letta.mobile.data.timeline.*
import kotlinx.coroutines.launch

@Composable
internal fun DesktopDeferredWindow(presentation: CanonicalTimelinePresentation, row: CanonicalTimelinePresentation.Row, dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
    val scope = rememberCoroutineScope()
    var text by remember(row) { mutableStateOf<String?>(null) }
    var offset by remember(row) { mutableStateOf(0L) }
    var next by remember(row) { mutableStateOf<Long?>(null) }
    val history = remember(row) { java.util.ArrayDeque<Long>() }
    var busy by remember(row) { mutableStateOf(false) }
    var job by remember(row) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(presentation, row) { onDispose { job?.cancel() } }
    var chosenField by remember(row) { mutableStateOf<TimelineSemanticField?>(null) }
    fun load(at: Long) {
        job?.cancel()
        job = scope.launch {
            busy = true; text = null; next = null; offset = at
            try {
                // Which string a stored body shows is discovered per row, not assumed to be
                // `content` - for a tool call that field holds a serialization artifact.
                val read: suspend (TimelineSemanticField, Long) -> TimelineSemanticWindowResult =
                    { chosen, from -> presentation.readTextWindow(row, chosen, from, dispatcher) }
                val result = chosenField?.let { read(it, at) } ?: resolveDeferredBody(read).let { body ->
                    chosenField = body.field
                    body.first ?: TimelineSemanticWindowResult.Deferred(
                        body.reason ?: TimelineSemanticWindowResult.Reason.MissingField, body.messageType,
                    )
                }
                when (result) {
                    is TimelineSemanticWindowResult.Text -> { text = result.value; next = result.nextScalarOffset }
                    is TimelineSemanticWindowResult.Deferred -> text = "Content remains deferred: ${result.reason}"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { text = failure.message ?: "Window unavailable; retry" }
            finally { busy = false }
        }
    }
    androidx.compose.foundation.layout.Column {
        text?.let { Text(it) }
        if (busy) Text("Loading content window...")
        TextButton(onClick = { load(offset) }, enabled = !busy) { Text(if (text == null) "View content" else "Retry window") }
        TextButton(onClick = { load(if (history.isEmpty()) 0 else history.removeLast()) }, enabled = !busy && offset > 0) { Text("Previous window") }
        TextButton(onClick = { next?.let { history.addLast(offset); if (history.size > 128) history.removeFirst(); load(it) } }, enabled = !busy && next != null) { Text("Next window") }
    }
}
