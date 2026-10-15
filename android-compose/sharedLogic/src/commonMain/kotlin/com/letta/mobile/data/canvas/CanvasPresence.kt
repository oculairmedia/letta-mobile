package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.InMemoryPresenceTransport
import com.letta.mobile.data.document.PeerPresence
import com.letta.mobile.data.document.PresenceTransport
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Ephemeral peer presence for collaborative canvas editing: a shared-document [PeerPresence] with
 * a cursor on the board.
 */
@Serializable
data class CanvasPresence(
    override val peerId: String,
    val displayName: String,
    val colorHex: String,
    val cursorX: Float,
    val cursorY: Float,
    override val lastActiveEpochMs: Long = 0L,
    override val isActive: Boolean = true,
) : PeerPresence

/**
 * Transport contract for peer presence telemetry: the shared-document [PresenceTransport] for
 * canvases. Completely separate from persistent scene operations.
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") // canvas callers and implementers name the id canvasId
interface CanvasPresenceTransport : PresenceTransport<CanvasId, CanvasPresence> {
    override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence)
    override fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>>
}

/**
 * In-memory presence transport with automated TTL expiry (peers inactive for > 10s or isActive=false removed).
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") // keeps the canvas's canvasId names for named callers
class InMemoryCanvasPresenceTransport(
    ttlMs: Long = InMemoryPresenceTransport.DEFAULT_TTL_MS,
    clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : InMemoryPresenceTransport<CanvasId, CanvasPresence>(
    ttlMs,
    clock,
    { presence, now -> presence.copy(lastActiveEpochMs = now) },
),
    CanvasPresenceTransport {
    override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence) =
        super.updatePresence(canvasId, presence)

    override fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>> = super.observePresence(canvasId)
}
