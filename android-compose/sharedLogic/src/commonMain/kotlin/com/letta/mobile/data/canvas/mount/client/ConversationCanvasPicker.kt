package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The board seat of the page on screen: [target]'s, switching through [board]. */
private class BoardSeat(val target: ConversationCanvasTarget, val board: ConversationBoardSwitch)

/**
 * The conversation whose page is on screen, and whether its board is on show with the chat over it
 * (canvas mode), for the window. The chat page reports it ([show] / [hide] while the page is
 * composed, with its board seat, [enter] / [leave] for canvas mode); the side menu reads it. Scoped
 * like the window's chat session state: one per window, never shared across windows or accounts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasModeFocus {
    private val page = MutableStateFlow<ConversationCanvasTarget?>(null)
    private val board = MutableStateFlow<ConversationCanvasTarget?>(null)
    private val seat = MutableStateFlow<BoardSeat?>(null)

    /** The conversation whose page is on screen, in either mode; null on a page without one (Home). */
    val onScreen: StateFlow<ConversationCanvasTarget?> = page.asStateFlow()

    /** The conversation in canvas mode, or null when no board is on show. */
    val current: StateFlow<ConversationCanvasTarget?> = board.asStateFlow()

    /**
     * The mounted board actually on show for the conversation on screen, once its seat has saved the
     * board before it and switched; null for its own board, or without a seat.
     */
    val shownBoard: Flow<CanvasId?> = seat.flatMapLatest { it?.board?.shown ?: flowOf(null) }

    /** [target]'s page is now on screen, its board switching through [board] (null: no seat to save). */
    fun show(target: ConversationCanvasTarget, board: ConversationBoardSwitch? = null) {
        page.value = target
        seat.value = board?.let { BoardSeat(target, it) }
    }

    /** [target]'s page left the screen (and with it canvas mode); a newer conversation shown since is kept. */
    fun hide(target: ConversationCanvasTarget) {
        page.update { if (it == target) null else it }
        seat.update { if (it?.target == target) null else it }
        leave(target)
    }

    /**
     * The board on show for [target]'s page: what its seat switched to (it keeps the board before a
     * mount while that cannot be saved), or [mounted] where no seat reports one.
     */
    internal fun displayed(target: ConversationCanvasTarget, mounted: CanvasId?): Flow<CanvasId?> =
        seat.flatMapLatest { it?.takeIf { seated -> seated.target == target }?.board?.shown ?: flowOf(mounted) }

    /** Saves [target]'s board on screen before a pick switches it; true when it saved or there is none to save. */
    suspend fun saveBoard(target: ConversationCanvasTarget): Boolean =
        seat.value?.takeIf { it.target == target }?.board?.save() ?: true

    /** [target]'s board is now on show, so its page is on screen too. */
    fun enter(target: ConversationCanvasTarget) {
        page.value = target
        board.value = target
    }

    /** [target]'s board left the screen; its page may stay. A newer conversation that entered since is kept. */
    fun leave(target: ConversationCanvasTarget) {
        board.update { if (it == target) null else it }
    }
}

/** What the side menu draws for canvases: whether a pick mounts, what is mounted now, and in which mode. */
data class CanvasPickerState(
    /** The conversation on screen whose board a pick mounts on; null while a pick opens as before. */
    val target: ConversationCanvasTarget? = null,
    /** The canvas mounted for [target], or null when it shows its own board. */
    val mounted: CanvasId? = null,
    /** Whether [target]'s board is on show with the chat over it (canvas mode). */
    val canvasMode: Boolean = false,
    /** Who the mount reaches: the host's, or this device's only (a host without mounts). */
    val reach: CanvasMountReach = CanvasMountReach.Host,
    /** The mounted board actually on show (the board before [mounted] stays while it cannot be saved); null for its own. */
    val shown: CanvasId? = mounted,
) {
    /** Whether picking a canvas mounts it (else it opens as it always has). */
    val mountsOnPick: Boolean get() = target != null

    /** The board on show in canvas mode (the one switched to, else the conversation's own); null outside canvas mode. */
    val onBoard: CanvasId? get() = target?.takeIf { canvasMode }?.let { shown ?: it.ownCanvas }
}

/** What became of one pick in the side menu. */
sealed interface CanvasPick {
    /** No conversation on screen, or the host has no mounts: the host opens the canvas as it always has. */
    data object OpenAsBefore : CanvasPick

    /** The conversation's board switched; [change] says how. */
    data class Mounted(val change: CanvasMountChange.Applied) : CanvasPick

    /** The board on screen could not be saved first, so nothing was switched and it stays, with its drawing. */
    data object Unsaved : CanvasPick

    /** The switch did not happen, for [problem]; the board stays as it was. */
    data class Failed(val problem: CanvasMountProblem) : CanvasPick
}

/** The conversation a pick is about and whether its board is on show. */
private data class PickFocus(val target: ConversationCanvasTarget, val canvasMode: Boolean)

/**
 * The side menu's canvas pick: while a conversation is on screen, picking a canvas mounts it for
 * that conversation (and only that one), in canvas mode and chat mode alike; what else the pick
 * shows (a side pane in chat mode) is the menu's call. With no conversation on screen, or on a host
 * without mounts, it opens as before. The board on screen is saved before the mount changes, so a
 * failed save leaves the mount, the board and the agent's tools where they were.
 * Shared by every host, so the decision is made once.
 */
class ConversationCanvasPicker(
    val resolver: ConversationCanvasResolver,
    val focus: CanvasModeFocus = CanvasModeFocus(),
) {
    /** The side menu's view, live. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<CanvasPickerState> = combine(focus.onScreen, focus.current, resolver.supported, ::pickFocus)
        .distinctUntilChanged()
        .flatMapLatest { focus -> focus?.let(::stateOf) ?: flowOf(CanvasPickerState()) }

    /** The conversation a pick would mount on now, or null when a pick opens as before. */
    fun mountTarget(): ConversationCanvasTarget? = focus.onScreen.value.takeIf { resolver.supported.value }

    /** Whether the conversation a pick mounts on has its board on show (canvas mode). */
    fun inCanvasMode(): Boolean = mountTarget()?.let { it == focus.current.value } ?: false

    /** Mounts [canvas] on the conversation on screen, or says to open it as before. */
    suspend fun pick(canvas: CanvasId): CanvasPick = switchBoard { resolver.mount(it, canvas) }

    /** Puts the conversation on screen back on its own board. */
    suspend fun useOwnCanvas(): CanvasPick = switchBoard(resolver::unmount)

    /** Saves the board on screen, then [write]s the mount of the conversation on screen. */
    private suspend fun switchBoard(write: suspend (ConversationCanvasTarget) -> CanvasMountChange): CanvasPick {
        val target = mountTarget() ?: return CanvasPick.OpenAsBefore
        if (!focus.saveBoard(target)) return CanvasPick.Unsaved
        return outcome(write(target))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun stateOf(pick: PickFocus): Flow<CanvasPickerState> =
        combine(resolver.state(pick.target), resolver.reach, ::Pair).flatMapLatest { (state, reach) ->
            val mounted = state.mountedCanvas
            focus.displayed(pick.target, mounted).map { shown -> CanvasPickerState(pick.target, mounted, pick.canvasMode, reach, shown) }
        }

    private fun outcome(change: CanvasMountChange): CanvasPick = when (change) {
        is CanvasMountChange.Applied -> CanvasPick.Mounted(change)
        is CanvasMountChange.Failed -> CanvasPick.Failed(change.problem)
    }

    private companion object {
        fun pickFocus(shown: ConversationCanvasTarget?, onBoard: ConversationCanvasTarget?, supported: Boolean): PickFocus? =
            shown?.takeIf { supported }?.let { PickFocus(it, canvasMode = it == onBoard) }
    }
}
