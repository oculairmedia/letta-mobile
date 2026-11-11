package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
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
 * Owned by the window, like [live]; it must not be shared across authentication contexts. When
 * [live] is omitted this owner creates [registry]; hosts publish that same registry to their views.
 */
class CanvasBoardSessions(
    private val store: CanvasDocumentStore,
    private val options: CanvasConversationOptions = CanvasConversationOptions(),
    live: CanvasSessionRegistry? = null,
) {
    /** The window's live mounts, shared by its composition, table and external tools. */
    val registry: CanvasSessionRegistry = live ?: CanvasSessionRegistry()

    /** Only one open at a time, so two asks for one board never open it twice. */
    private val opening = Mutex()

    /** Each held board's session and its number of holds; pairs avoid adding a type here. */
    private val held = MutableStateFlow<Map<CanvasId, Pair<CanvasSession, Int>>>(emptyMap())

    /**
     * A hold on the live session of the stored board [canvasId], opened from the store when no one
     * has it yet; null, and no hold, when the store has no such board. A store failure propagates.
     */
    suspend fun acquire(canvasId: CanvasId): CanvasSession? =
        acquireOrOpen(canvasId) { CanvasSession.open(store, canvasId, options) }

    /**
     * Acquires [canvasId], invoking the host's existing [open] path only when neither a holder nor
     * a live mount has it. Serializes creation too, so conversation pages and by-id modules cannot
     * race into separate sessions. The returned session must identify [canvasId].
     */
    private suspend fun acquireOrOpen(canvasId: CanvasId, open: suspend () -> CanvasSession?): CanvasSession? =
        holdExisting(canvasId) ?: opening.withLock {
            holdExisting(canvasId) ?: (registry.get(canvasId) ?: open())?.also { session ->
                require(session.canvasId == canvasId) { "Opened canvas does not match the requested board" }
                update { it + (canvasId to (session to 1)) }
            }
        }

    /**
     * Acquires a page's explicit board or its conversation board, creating through the existing
     * session APIs only when no live session exists. A page without either gets a new board.
     * The page releases this hold when its mount leaves, independently of its navigation owner.
     */
    suspend fun acquirePage(canvasId: CanvasId?, conversationId: ConversationId?, agentId: AgentId?): CanvasSession {
        val conversation = conversationId.takeIf { canvasId == null }
        val resolved = when {
            canvasId != null -> canvasId
            conversation != null -> store.getForConversation(conversation)?.id ?: CanvasId.forConversation(conversation)
            else -> CanvasId.generate()
        }
        return checkNotNull(acquireOrOpen(resolved) {
            when (conversation) {
                null -> CanvasSession(resolved, store, opLog = options.opLog, syncTransport = options.syncTransport).also { it.load() }
                else -> CanvasSession.getOrCreateForConversation(store, conversation, options.copy(agentId = agentId?.value))
            }
        })
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

    /**
     * Forgets this owner's holds and live mounts when its window finishes or its backend changes.
     * Later disposers/releases from the old views are harmless; sync remains owned by those views.
     */
    fun clear() {
        update { emptyMap() }
        registry.clear()
    }

    /** The number of holds on [canvasId]'s session; zero when it is not held. */
    fun holds(canvasId: CanvasId): Int = held.value[canvasId]?.second ?: 0

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
