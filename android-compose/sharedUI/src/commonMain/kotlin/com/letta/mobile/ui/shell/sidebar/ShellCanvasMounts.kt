package com.letta.mobile.ui.shell.sidebar

import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.Flow

/**
 * The window's canvas mounts as the side menu sees them: what a canvas pick does now
 * ([ShellCanvasPickPlan]), which canvas is mounted for the conversation on screen, and the pick itself. The host's canvas
 * picker implements it (see `ui.canvas.mount`), so the menu knows nothing of how mounts are resolved.
 */
interface ShellCanvasMounts {
    /** What the menu draws for canvases, live. */
    val state: Flow<ShellCanvasMountState>

    /** What a pick does right now. */
    fun pickPlan(): ShellCanvasPickPlan

    /** The own canvas of the conversation on screen right now (it names the conversation); null with none. */
    fun ownOnScreen(): CanvasId?

    /** Mounts [canvas] on the conversation on screen. */
    suspend fun pick(canvas: CanvasId): ShellCanvasPickOutcome

    /** Puts the conversation on screen back on its own canvas. */
    suspend fun useOwn(): ShellCanvasPickOutcome
}

/** What a canvas pick in the side menu does now. */
enum class ShellCanvasPickPlan {
    /** No conversation on screen (Home): the host opens the canvas as it always has. */
    Open,

    /** Canvas mode: the board behind the chat swaps to the canvas in place; no pane opens. */
    Mount,

    /** Chat mode: the host opens the canvas as it always has (a side pane) and it is mounted for the conversation. */
    OpenAndMount,
}

/** What the side menu draws for canvas mounts. */
data class ShellCanvasMountState(
    /** Whether a pick mounts (a conversation is on screen). */
    val mountsOnPick: Boolean = false,
    /** The canvas mounted for the conversation on screen; null while it shows its own. */
    val mounted: CanvasId? = null,
    /** The conversation on screen's own canvas, the one the way back returns to; null with none on screen. */
    val own: CanvasId? = null,
    /** The mount is held on this device only (a host without mounts); the agent's tools keep the conversation's own canvas. */
    val deviceOnly: Boolean = false,
    /**
     * Canvases the menu's "Open in side panel" leaves out: the conversation's own (it lives under the
     * chat) and the board on show in canvas mode (already on screen).
     */
    val notForPane: Set<CanvasId> = emptySet(),
)

/** What became of one pick, in the words the menu tells the person. */
enum class ShellCanvasPickOutcome {
    /** No conversation on screen (any more): the host opens the canvas as it always has. */
    OpenAsBefore,

    /** The conversation's board switched. */
    Mounted,

    /** The board on screen could not be saved first, so it stays and nothing switched. */
    Unsaved,

    /** This device may not switch this conversation's canvas. */
    Denied,

    /** The host refused the canvas itself. */
    Invalid,

    /** The host could not switch it now. */
    Unavailable,

    /** The host could not confirm whether it switched; the board shows what the host holds. */
    Unknown,
}

/** The window's canvas mounts; null (the default) is a host without them, where a pick opens as before. */
val LocalShellCanvasMounts = staticCompositionLocalOf<ShellCanvasMounts?> { null }
