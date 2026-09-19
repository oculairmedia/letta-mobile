package com.letta.mobile.ui.canvas

/** Which nib is touching the tablet. */
enum class CanvasPenTool {
    /** A pen, pencil, brush or airbrush: something that draws. */
    DRAW,

    /** The eraser end of the stylus. Flipping the pen over is a tool change, not a mode change. */
    ERASER,

    /** A tool emulated from the mouse, or one we could not identify. */
    OTHER,
}

/** What the pen just did, in the drawing surface's own logical coordinates. */
data class CanvasPenEvent(
    val phase: Phase,
    val x: Float,
    val y: Float,
    /** Nib force in `0..1`, or null when the tool has no pressure axis. */
    val pressure: Float?,
    val tool: CanvasPenTool,
) {
    enum class Phase { IN, DOWN, MOVE, UP, OUT }
}

/**
 * The seam between a platform's tablet and the canvas.
 *
 * A pen has to do two different jobs. Pressing buttons, picking notes and dragging handles is
 * ordinary input, and the platform delivers it as such. Drawing is not: a stroke carries pressure
 * per sample, which no mouse event can express, so the canvas takes those events itself and builds
 * the stroke from them.
 *
 * The consumer returns true for the events it has taken, and the platform then does NOT also
 * deliver them as mouse input — otherwise a stroke would be drawn twice, once with pressure and
 * once without.
 *
 * Desktop is the only platform with a tablet bridge today (Android delivers a stylus through
 * Compose already, with pressure intact), which is why this is a plain hook rather than an expect
 * declaration.
 */
object CanvasPenInput {
    /** Set by the canvas while it is on screen; null when nothing is listening. */
    var consumer: ((CanvasPenEvent) -> Boolean)? = null
}
