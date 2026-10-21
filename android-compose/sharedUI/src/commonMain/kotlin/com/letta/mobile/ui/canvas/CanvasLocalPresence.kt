package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private const val POINTER_PERIOD_MS = 100L
private const val HEARTBEAT_MS = 1_000L
private val PEER_COLORS = listOf("#2196F3", "#9C27B0", "#00897B", "#EF6C00", "#C62828")

private class LocalCursor {
    var position: Offset? = null
    var active = false
}

/** Observes Initial-pass input without taking any event from the board or its notes. */
@Composable
internal fun Modifier.canvasLocalPresence(
    transport: CanvasPresenceTransport?,
    canvasId: CanvasId?,
    peerId: String?,
    screenToWorld: (Offset) -> Offset,
): Modifier {
    if (transport == null || canvasId == null || peerId == null) return this
    val cursor = remember(transport, canvasId, peerId) { LocalCursor() }
    val toWorld = rememberUpdatedState(screenToWorld)
    LaunchedEffect(transport, canvasId, peerId) {
        val color = PEER_COLORS[(peerId.hashCode().toUInt() % PEER_COLORS.size.toUInt()).toInt()]
        var last: CanvasPresence? = null
        var elapsed = 0L
        try {
            while (true) {
                delay(POINTER_PERIOD_MS)
                elapsed += POINTER_PERIOD_MS
                val point = cursor.position ?: continue
                val presence = CanvasPresence(
                    peerId = peerId,
                    displayName = "Peer ${peerId.take(8)}",
                    colorHex = color,
                    cursorX = point.x,
                    cursorY = point.y,
                    lastActiveEpochMs = Clock.System.now().toEpochMilliseconds(),
                    isActive = cursor.active,
                )
                val changed = last?.let {
                    it.cursorX != point.x || it.cursorY != point.y || it.isActive != cursor.active
                } ?: true
                if (changed || elapsed >= HEARTBEAT_MS) {
                    transport.updatePresence(canvasId, presence)
                    last = presence
                    elapsed = 0L
                }
            }
        } finally {
            val point = cursor.position ?: Offset.Zero
            withContext(NonCancellable) {
                transport.updatePresence(
                    canvasId,
                    CanvasPresence(peerId, "Peer ${peerId.take(8)}", color, point.x, point.y,
                        lastActiveEpochMs = Clock.System.now().toEpochMilliseconds(), isActive = false),
                )
            }
        }
    }
    return pointerInput(cursor) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull()?.let { change ->
                    cursor.position = toWorld.value(change.position)
                    cursor.active = event.type != PointerEventType.Exit
                }
            }
        }
    }
}
