package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The board on screen's last chance to save before another board replaces it. The board attaches
 * its save of the drawing in progress; a switch waits for it ([prepare]).
 */
class CanvasBoardSwitchGate {
    private var flush: (suspend () -> Boolean)? = null

    /** [save] runs before a switch; true once everything drawn is stored. Returns the detach. */
    fun attach(save: suspend () -> Boolean): () -> Unit {
        flush = save
        return { if (flush === save) flush = null }
    }

    /** Saves the board on screen; false when the save failed and the board must stay. */
    suspend fun prepare(): Boolean = flush?.invoke() ?: true
}

/**
 * Which board one conversation's page shows as its [ConversationCanvasState] changes, never
 * losing an edit. The rule for an edit in progress when a switch arrives: the board on screen is
 * saved first ([CanvasBoardSwitchGate.prepare]); only once the save succeeds does the page switch.
 * A failed save keeps the current board (and its unsaved drawing, with the board's own save error)
 * and [follow] answers false, so the caller asks again later.
 *
 * While the host is still being asked the page keeps what it shows; an unavailable host puts the
 * conversation's own board back, as before mounts existed.
 */
class ConversationBoardSwitch(private val gate: CanvasBoardSwitchGate) {
    private val showing = MutableStateFlow<CanvasId?>(null)

    /** The mounted board on show, or null for the conversation's own board. */
    val shown: StateFlow<CanvasId?> = showing.asStateFlow()

    /** Saves the board on screen before something else switches it; false when the save failed and the board must stay. */
    suspend fun save(): Boolean = gate.prepare()

    /** Moves to the board [state] asks for; false when the current board could not be saved first. */
    suspend fun follow(state: ConversationCanvasState): Boolean {
        if (state == ConversationCanvasState.Resolving) return true
        val wanted = state.mountedCanvas
        if (wanted == showing.value) return true
        if (!gate.prepare()) return false
        showing.value = wanted
        return true
    }
}
