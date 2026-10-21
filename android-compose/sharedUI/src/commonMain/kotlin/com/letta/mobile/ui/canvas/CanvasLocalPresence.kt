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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Cursor updates are sent at most this often, however fast the pointer moves. */
private val POINTER_THROTTLE = 100.milliseconds

/** While the pointer rests on the board, its presence is re-sent this often so peers keep it live. */
private val HEARTBEAT = 1.seconds

/** How long leaving the board may wait for the transport to accept the departure. */
private val DEPARTURE_TIMEOUT = 500.milliseconds

/** Cursor colours, chosen per peer ID so every device shows a peer in the same colour. */
internal val CANVAS_PEER_COLORS = listOf("#2196F3", "#9C27B0", "#00897B", "#EF6C00", "#C62828")

/**
 * Publishes this device's pointer on the board, in world coordinates, as [identity]. Observes
 * Initial-pass input without taking any event from the board or its notes. Nothing runs while
 * the pointer is still: publication suspends until it moves, and the heartbeat runs only while
 * the pointer is on the board. Leaving the composition reports the cursor inactive.
 */
@Composable
internal fun Modifier.canvasLocalPresence(
    transport: CanvasPresenceTransport?,
    canvasId: CanvasId?,
    identity: CanvasPresence?,
    screenToWorld: (Offset) -> Offset,
): Modifier {
    transport ?: return this
    canvasId ?: return this
    identity ?: return this
    val pointer = remember(transport, canvasId, identity) { MutableStateFlow<CanvasPresence?>(null) }
    val toWorld = rememberUpdatedState(screenToWorld)
    LaunchedEffect(pointer) { publishLocalPresence(transport, canvasId, pointer) }
    return pointerInput(pointer) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                val world = toWorld.value(change.position)
                pointer.value = identity.copy(cursorX = world.x, cursorY = world.y, isActive = event.type != PointerEventType.Exit)
            }
        }
    }
}

private suspend fun publishLocalPresence(
    transport: CanvasPresenceTransport,
    canvasId: CanvasId,
    pointer: StateFlow<CanvasPresence?>,
) {
    var sent: CanvasPresence? = null
    try {
        while (true) {
            val wait = if (sent?.isActive == true) HEARTBEAT else Duration.INFINITE
            val next = withTimeoutOrNull(wait) { pointer.first { it != null && it != sent } } ?: sent ?: continue
            transport.updatePresence(canvasId, next.stamped())
            sent = next
            delay(POINTER_THROTTLE)
        }
    } finally {
        pointer.value?.let { departCanvasPresence(transport, canvasId, it) }
    }
}

/** Best-effort, bounded departure: a stalled or closed transport must not hold up disposal; the peer TTL also expires it. */
internal suspend fun departCanvasPresence(transport: CanvasPresenceTransport, canvasId: CanvasId, last: CanvasPresence) {
    withContext(NonCancellable) {
        try {
            withTimeoutOrNull(DEPARTURE_TIMEOUT) { transport.updatePresence(canvasId, last.copy(isActive = false).stamped()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Departure is best-effort; the remote presence TTL also removes this peer.
        }
    }
}

private fun CanvasPresence.stamped() = copy(lastActiveEpochMs = Clock.System.now().toEpochMilliseconds())
