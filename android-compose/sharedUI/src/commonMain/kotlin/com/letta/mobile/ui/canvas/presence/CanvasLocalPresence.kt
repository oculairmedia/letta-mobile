package com.letta.mobile.ui.canvas.presence

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

private const val POINTER_PERIOD_MS = 100L
private const val HEARTBEAT_MS = 1_000L
private const val DEPARTURE_TIMEOUT_MS = 500L
private val PEER_COLORS = listOf("#2196F3", "#9C27B0", "#00897B", "#EF6C00", "#C62828")

internal data class LocalCursorPosition(val position: Offset, val active: Boolean)

internal class LocalCursor {
    var current: LocalCursorPosition? = null
        private set

    fun observe(type: PointerEventType, position: Offset?) {
        if (type == PointerEventType.Exit) {
            current = current?.copy(active = false)
            return
        }
        if (position != null) current = LocalCursorPosition(position, active = true)
    }
}

private data class PresencePeer(val id: String) {
    private val color = PEER_COLORS[(id.hashCode().toUInt() % PEER_COLORS.size.toUInt()).toInt()]
    fun presence(cursor: LocalCursorPosition): CanvasPresence = CanvasPresence(
        peerId = id,
        displayName = "Peer ${id.take(8)}",
        colorHex = color,
        cursorX = cursor.position.x,
        cursorY = cursor.position.y,
        lastActiveEpochMs = Clock.System.now().toEpochMilliseconds(),
        isActive = cursor.active,
    )
}

private class PresenceHeartbeat {
    private var elapsed = 0L
    private var last: LocalCursorPosition? = null

    fun due(cursor: LocalCursorPosition): Boolean {
        elapsed += POINTER_PERIOD_MS
        if (cursor == last && elapsed < HEARTBEAT_MS) return false
        last = cursor
        elapsed = 0L
        return true
    }
}

internal suspend fun departCanvasPresence(
    transport: CanvasPresenceTransport,
    canvasId: CanvasId,
    presence: CanvasPresence,
) {
    withContext(NonCancellable) {
        try {
            withTimeoutOrNull(DEPARTURE_TIMEOUT_MS) {
                transport.updatePresence(canvasId, presence.copy(isActive = false))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Departure is best-effort; the remote presence TTL also removes this peer.
        }
    }
}

private suspend fun publishPresence(
    transport: CanvasPresenceTransport,
    canvasId: CanvasId,
    peer: PresencePeer,
    cursor: LocalCursor,
) {
    val heartbeat = PresenceHeartbeat()
    try {
        while (true) {
            delay(POINTER_PERIOD_MS)
            val current = cursor.current ?: continue
            if (heartbeat.due(current)) transport.updatePresence(canvasId, peer.presence(current))
        }
    } finally {
        val inactive = LocalCursorPosition(cursor.current?.position ?: Offset.Zero, active = false)
        departCanvasPresence(transport, canvasId, peer.presence(inactive))
    }
}

/** Observes Initial-pass input without taking any event from the board or its notes. */
@Composable
internal fun Modifier.canvasLocalPresence(
    transport: CanvasPresenceTransport?,
    canvasId: CanvasId?,
    peerId: String?,
    screenToWorld: (Offset) -> Offset,
): Modifier {
    val liveTransport = transport ?: return this
    val liveCanvas = canvasId ?: return this
    val livePeer = peerId ?: return this
    val cursor = remember(liveTransport, liveCanvas, livePeer) { LocalCursor() }
    val toWorld = rememberUpdatedState(screenToWorld)
    LaunchedEffect(cursor) {
        publishPresence(liveTransport, liveCanvas, PresencePeer(livePeer), cursor)
    }
    return pointerInput(cursor) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val position = event.changes.firstOrNull()?.position?.let(toWorld.value)
                cursor.observe(event.type, position)
            }
        }
    }
}
