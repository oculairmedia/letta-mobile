package com.letta.mobile.ui.canvas.boards

import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import kotlin.reflect.KProperty

/**
 * The board a page shows, as observable state a host can delegate a property to. A board the page
 * opened through the window's [boards] is shown with [take], and the page keeps that hold until it
 * shows something else or nothing; a board set from anywhere else is shown without a hold.
 */
class CanvasPageBoard(private val boards: CanvasBoardSessions) {
    private val shown = mutableStateOf<CanvasSession?>(null)

    /** The page's hold, when what it shows came through [boards]. */
    private var held: CanvasSession? = null

    /** The board the page shows; replacing it gives back the page's hold on the one before. */
    var session: CanvasSession?
        get() = shown.value
        set(value) {
            shown.value = value
            val previous = held
            if (previous == null || previous === value) return
            held = null
            boards.release(previous)
        }

    /**
     * Shows [session], a hold from [boards] the page now keeps. The page keeps one hold per board,
     * so a second hold on the board it already shows goes straight back.
     */
    fun take(session: CanvasSession) {
        this.session = session
        if (held === session) boards.release(session) else held = session
    }

    /** [session], for a host property delegated to this page board. */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): CanvasSession? = session

    /** Sets [session], for a host property delegated to this page board. */
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: CanvasSession?) {
        session = value
    }
}
