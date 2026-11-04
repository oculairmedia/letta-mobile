package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
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
    private val nextMountId = MutableStateFlow(0L)

    private val activeSessions = MutableStateFlow<Map<CanvasId, List<Pair<Long, CanvasSession>>>>(emptyMap())

    /** Replaces registrations for this id. Mounts should use [registerMount] instead. */
    fun register(session: CanvasSession) {
        update { it + (session.canvasId to listOf(newMountId() to session)) }
    }

    /**
     * Registers one mount and returns its idempotent disposer. The newest live mount is used by
     * [get]; disposing it reveals the previous live mount in this same owner-provided scope.
     * Callers must not share a registry across authentication contexts.
     */
    fun registerMount(session: CanvasSession): () -> Unit {
        // A per-registry mount id; pairs avoid adding a type to the canvas package.
        val registration = newMountId() to session
        val canvasId = session.canvasId
        update { current -> current + (canvasId to (current[canvasId].orEmpty() + registration)) }
        return {
            update { current ->
                val remaining = current[canvasId].orEmpty().filterNot { it.first == registration.first }
                if (remaining.isEmpty()) current - canvasId else current + (canvasId to remaining)
            }
        }
    }

    /** Removes all registrations for an id. Mounts should dispose their own registration. */
    fun unregister(canvasId: CanvasId) {
        update { it - canvasId }
    }

    fun get(canvasId: CanvasId): CanvasSession? = activeSessions.value[canvasId]?.lastOrNull()?.second

    fun clear() {
        update { emptyMap() }
    }

    private fun update(transform: (Map<CanvasId, List<Pair<Long, CanvasSession>>>) -> Map<CanvasId, List<Pair<Long, CanvasSession>>>) {
        while (true) {
            val current = activeSessions.value
            if (activeSessions.compareAndSet(current, transform(current))) return
        }
    }

    private fun newMountId(): Long {
        while (true) {
            val current = nextMountId.value
            if (nextMountId.compareAndSet(current, current + 1)) return current
        }
    }
}
