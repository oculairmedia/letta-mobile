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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.jvm.JvmInline

private const val POINTER_PERIOD_MS = 100L
private const val HEARTBEAT_MS = 1_000L
private const val DEPARTURE_TIMEOUT_MS = 500L
private val PEER_COLORS = listOf("#2196F3", "#9C27B0", "#00897B", "#EF6C00", "#C62828")

internal data class LocalCursorPosition(val position: Offset, val active: Boolean)

/** Ephemeral canvas peer identity, distinct from a canvas actor or a controller host. */
@JvmInline
internal value class CanvasPresencePeerId(val value: String)

internal data class PresenceIdentity(
    val peerId: CanvasPresencePeerId,
    val displayName: String,
    val colorHex: String,
) {
    companion object {
        fun forPeer(peerId: CanvasPresencePeerId): PresenceIdentity {
            val index = (peerId.value.hashCode().toUInt() % PEER_COLORS.size.toUInt()).toInt()
            return PresenceIdentity(peerId, "Peer ${peerId.value.take(8)}", PEER_COLORS[index])
        }
    }
}

internal data class LocalPeerPresence(
    val identity: PresenceIdentity,
    val cursor: LocalCursorPosition,
    val lastActiveEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
)

/** The board binds its session/transport here; pointer observation does not depend on board models. */
internal class LocalPresenceTarget(
    val peerId: CanvasPresencePeerId,
    val publish: suspend (LocalPeerPresence) -> Unit,
)

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

internal suspend fun departCanvasPresence(target: LocalPresenceTarget, presence: LocalPeerPresence) {
    withContext(NonCancellable) {
        try {
            withTimeoutOrNull(DEPARTURE_TIMEOUT_MS) {
                target.publish(presence.copy(cursor = presence.cursor.copy(active = false)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Departure is best-effort; the remote presence TTL also removes this peer.
        }
    }
}

private suspend fun publishPresence(target: LocalPresenceTarget, cursor: LocalCursor) {
    val heartbeat = PresenceHeartbeat()
    val identity = PresenceIdentity.forPeer(target.peerId)
    try {
        while (true) {
            delay(POINTER_PERIOD_MS)
            val current = cursor.current ?: continue
            if (heartbeat.due(current)) target.publish(LocalPeerPresence(identity, current))
        }
    } finally {
        val inactive = LocalCursorPosition(cursor.current?.position ?: Offset.Zero, active = false)
        departCanvasPresence(target, LocalPeerPresence(identity, inactive))
    }
}

/** Observes Initial-pass input without taking any event from the board or its notes. */
@Composable
internal fun Modifier.canvasLocalPresence(target: LocalPresenceTarget?, screenToWorld: (Offset) -> Offset): Modifier {
    val liveTarget = target ?: return this
    val cursor = remember(liveTarget) { LocalCursor() }
    val toWorld = rememberUpdatedState(screenToWorld)
    LaunchedEffect(cursor) { publishPresence(liveTarget, cursor) }
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
