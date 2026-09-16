package com.letta.mobile.data.canvas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory registry of active [CanvasSession] instances.
 *
 * Allows external tools (App Server agent commands) to dispatch scene replacements
 * and mutations directly to an active session, which then projects into the UI.
 */
object CanvasSessionRegistry {
    private val mutex = Mutex()
    private val activeSessions = mutableMapOf<CanvasId, CanvasSession>()

    suspend fun register(session: CanvasSession) {
        mutex.withLock {
            activeSessions[session.canvasId] = session
        }
    }

    suspend fun unregister(canvasId: CanvasId) {
        mutex.withLock {
            activeSessions.remove(canvasId)
        }
    }

    suspend fun get(canvasId: CanvasId): CanvasSession? {
        mutex.withLock {
            return activeSessions[canvasId]
        }
    }

    suspend fun clear() {
        mutex.withLock {
            activeSessions.clear()
        }
    }
}
