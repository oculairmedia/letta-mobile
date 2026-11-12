package com.letta.mobile.ui.shell.sidebar

import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.Flow

/**
 * The window's canvas mounts as the side menu sees them: whether a canvas pick mounts it on the
 * conversation in canvas mode, which canvas is mounted there, and the pick itself. The host's canvas
 * picker implements it (see `ui.canvas.mount`), so the menu knows nothing of how mounts are resolved.
 */
interface ShellCanvasMounts {
    /** What the menu draws for canvases, live. */
    val state: Flow<ShellCanvasMountState>

    /** Whether a pick mounts right now (canvas mode on a host with mounts); else it opens as before. */
    fun mountsOnPick(): Boolean

    /** Mounts [canvas] on the conversation in canvas mode. */
    suspend fun pick(canvas: CanvasId): ShellCanvasPickOutcome

    /** Puts the conversation in canvas mode back on its own canvas. */
    suspend fun useOwn(): ShellCanvasPickOutcome
}

/** What the side menu draws for canvas mounts. */
data class ShellCanvasMountState(
    /** Whether a pick mounts (canvas mode on a host with mounts). */
    val mountsOnPick: Boolean = false,
    /** The canvas mounted for the conversation in canvas mode; null while it shows its own. */
    val mounted: CanvasId? = null,
)

/** What became of one pick, in the words the menu tells the person. */
enum class ShellCanvasPickOutcome {
    /** Not in canvas mode (any more): the host opens the canvas as it always has. */
    OpenAsBefore,

    /** The conversation's board switched. */
    Mounted,

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
