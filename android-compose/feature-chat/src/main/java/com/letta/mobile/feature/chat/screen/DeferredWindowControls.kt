package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import kotlinx.coroutines.launch

/** One bounded window replaces the previous one. No read occurs before an explicit click. */
@Composable
internal fun DeferredWindowControls(
    identity: com.letta.mobile.data.chat.projection.ChatRenderItem,
    read: suspend (Long) -> TimelineSemanticWindowResult,
) {
    val scope = rememberCoroutineScope()
    var text by remember(identity) { mutableStateOf<String?>(null) }
    var offset by remember(identity) { mutableStateOf(0L) }
    var next by remember(identity) { mutableStateOf<Long?>(null) }
    val history = remember(identity) { java.util.ArrayDeque<Long>() }
    var busy by remember(identity) { mutableStateOf(false) }
    var job by remember(identity) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(identity) { onDispose { job?.cancel() } }
    fun load(at: Long) {
        job?.cancel()
        job = scope.launch {
            busy = true
            text = null
            next = null
            offset = at
            try {
                when (val result = read(at)) {
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
