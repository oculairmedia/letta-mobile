package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The live [CanvasSession]s an external tool can reach, so an agent command lands on the session
 * the user is looking at rather than only on the store behind it.
 *
 * An instance, not an object. Process-global mutable state would keep every canvas session alive
 * across account switches, backend switches and sign-out, and would let two conversations observe
 * each other's sessions — and it is only reachable for a test to reset by hand
 * (`NoProcessGlobalMutableState`). Whoever owns the session's lifetime owns one of these and hands
 * it to the tools that need it.
 *
 * Registration is reached from composition and lookup from tool dispatch, so the table is held in
 * a [MutableStateFlow] and updated by compare-and-set rather than behind a lock only one of those
 * callers could take.
 */
class CanvasSessionRegistry {
    private val activeSessions = MutableStateFlow<Map<CanvasId, CanvasSession>>(emptyMap())

    fun register(session: CanvasSession) {
        update { it + (session.canvasId to session) }
    }

    fun unregister(canvasId: CanvasId) {
        update { it - canvasId }
    }

    fun get(canvasId: CanvasId): CanvasSession? = activeSessions.value[canvasId]

    fun clear() {
        update { emptyMap() }
    }

    private fun update(transform: (Map<CanvasId, CanvasSession>) -> Map<CanvasId, CanvasSession>) {
        while (true) {
            val current = activeSessions.value
            if (activeSessions.compareAndSet(current, transform(current))) return
        }
    }
}
