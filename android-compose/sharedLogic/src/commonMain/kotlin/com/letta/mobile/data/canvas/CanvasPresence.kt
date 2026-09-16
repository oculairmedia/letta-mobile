package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Ephemeral peer presence for collaborative canvas editing.
 */
@Serializable
data class CanvasPresence(
    val peerId: String,
    val displayName: String,
    val colorHex: String,
    val cursorX: Float,
    val cursorY: Float,
    val lastActiveEpochMs: Long = 0L,
    val isActive: Boolean = true,
)

/**
 * Transport contract for peer presence telemetry.
 * Completely separate from persistent scene operations.
 */
interface CanvasPresenceTransport {
    suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence)
    fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>>
}

/**
 * In-memory presence transport with automated TTL expiry (peers inactive for > 10s or isActive=false removed).
 */
class InMemoryCanvasPresenceTransport(
    private val ttlMs: Long = 10_000L,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : CanvasPresenceTransport {
    private val mutex = Mutex()
    private val presencesByCanvas = mutableMapOf<CanvasId, MutableMap<String, CanvasPresence>>()
    private val flowsByCanvas = mutableMapOf<CanvasId, MutableStateFlow<List<CanvasPresence>>>()

    override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence) = mutex.withLock {
        val map = presencesByCanvas.getOrPut(canvasId) { mutableMapOf() }
        val now = clock()
        if (!presence.isActive) {
            map.remove(presence.peerId)
        } else {
            map[presence.peerId] = presence.copy(lastActiveEpochMs = now)
        }

        // Clean up expired peers
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastActiveEpochMs > ttlMs) {
                iterator.remove()
            }
        }

        val list = map.values.toList()
        val flow = flowsByCanvas.getOrPut(canvasId) { MutableStateFlow(emptyList()) }
        flow.value = list
    }

    override fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>> {
        val flow = synchronized(flowsByCanvas) {
            flowsByCanvas.getOrPut(canvasId) { MutableStateFlow(emptyList()) }
        }
        return flow.asStateFlow()
    }
}
