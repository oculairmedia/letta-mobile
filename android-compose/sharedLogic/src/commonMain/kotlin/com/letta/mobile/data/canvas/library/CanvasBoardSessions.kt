package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One live [CanvasSession] per stored board for everything in a window that opens boards by id
 * (a canvas module in a shell lane, the canvas library's page), so two views of one board never
 * hold diverging copies over the same [store].
 *
 * Each [acquire] is a hold on the session it returns, and each holder [release]s it when it stops
 * showing the board. The session is kept while anyone holds it and forgotten when the last hold
 * goes: a session owns nothing to close (its sync and its tool registration belong to the board
 * that mounts it), and a forgotten one is never handed out again, so a later open reads the store
 * afresh instead of a copy that may have gone stale while nothing showed it. A board already live
 * in [live] (mounted by a view that opened it some other way) is adopted rather than opened again.
 *
 * Owned by the window, like [live]; it must not be shared across authentication contexts.
 */
class CanvasBoardSessions(
    private val store: CanvasDocumentStore,
    private val options: CanvasConversationOptions = CanvasConversationOptions(),
    private val live: CanvasSessionRegistry? = null,
) {
    /** Only one open at a time, so two asks for one board never open it twice. */
    private val opening = Mutex()

    /** Each held board's session and its number of holds; pairs avoid adding a type here. */
    private val held = MutableStateFlow<Map<CanvasId, Pair<CanvasSession, Int>>>(emptyMap())

    /**
     * A hold on the live session of the stored board [canvasId], opened from the store when no one
     * has it yet; null, and no hold, when the store has no such board. A store failure propagates.
     */
    suspend fun acquire(canvasId: CanvasId): CanvasSession? =
        holdExisting(canvasId) ?: opening.withLock {
            holdExisting(canvasId) ?: openNew(canvasId)?.also { session ->
                update { it + (canvasId to (session to 1)) }
            }
        }

    /** Gives back one hold on [session]; one this table does not hold is ignored. */
    fun release(session: CanvasSession) {
        val canvasId = session.canvasId
        update { current ->
            val entry = current[canvasId]?.takeIf { it.first === session }
            when {
                entry == null -> current
                entry.second <= 1 -> current - canvasId
                else -> current + (canvasId to (session to entry.second - 1))
            }
        }
    }

    /** The number of holds on [canvasId]'s session; zero when it is not held. */
    fun holds(canvasId: CanvasId): Int = held.value[canvasId]?.second ?: 0

    private suspend fun openNew(canvasId: CanvasId): CanvasSession? =
        live?.get(canvasId) ?: CanvasSession.open(store, canvasId, options)

    /** One more hold on [canvasId]'s session when it is already held, else null. */
    private fun holdExisting(canvasId: CanvasId): CanvasSession? {
        var session: CanvasSession? = null
        update { current ->
            val entry = current[canvasId]
            session = entry?.first
            if (entry == null) current else current + (canvasId to (entry.first to entry.second + 1))
        }
        return session
    }

    private fun update(transform: (Map<CanvasId, Pair<CanvasSession, Int>>) -> Map<CanvasId, Pair<CanvasSession, Int>>) {
        while (true) {
            val current = held.value
            if (held.compareAndSet(current, transform(current))) return
        }
    }
}
