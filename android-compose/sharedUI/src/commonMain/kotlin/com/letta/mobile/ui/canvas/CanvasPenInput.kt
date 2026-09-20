package com.letta.mobile.ui.canvas

import kotlinx.coroutines.flow.MutableStateFlow

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
class CanvasPenRegistry {

    /**
     * One consumer per window, by that window's identity.
     *
     * A single consumer was a bug waiting for a second window: each canvas overwrote the other's
     * registration, the one that disposed first cleared whichever was current, and a pen event
     * from one window was offered coordinates belonging to another. Pressure and the eraser end
     * would go quiet in whichever window had not registered last, for no reason a person could
     * see.
     *
     * Registration is reached from composition and delivery from a poll loop on another thread,
     * so the table is a [MutableStateFlow] updated by compare-and-set rather than a bare map -
     * the same shape, and for the same reason, as [com.letta.mobile.data.canvas.CanvasSessionRegistry].
     */
    private val consumers = MutableStateFlow<Map<CanvasPenTarget, (CanvasPenEvent) -> Boolean>>(emptyMap())

    /**
     * Registers [consumer] for [target], returning a disposer.
     *
     * The disposer is identity-safe: it removes this registration and never a replacement, so a
     * canvas that is re-created before the old one disposes cannot be silenced by its
     * predecessor's teardown.
     */
    fun register(target: CanvasPenTarget, consumer: (CanvasPenEvent) -> Boolean): () -> Unit {
        update { it + (target to consumer) }
        return { update { current -> if (current[target] === consumer) current - target else current } }
    }

    /**
     * Offers [event] to the canvas in [target]'s window; false when it was not taken, including
     * when that window has no canvas listening. An event never crosses to another window.
     */
    fun deliver(target: CanvasPenTarget, event: CanvasPenEvent): Boolean =
        consumers.value[target]?.invoke(event) == true

    /** True when [target]'s window has a canvas listening, for hosts that poll only when it does. */
    fun hasConsumer(target: CanvasPenTarget): Boolean = consumers.value.containsKey(target)

    private fun update(transform: (Map<CanvasPenTarget, (CanvasPenEvent) -> Boolean>) -> Map<CanvasPenTarget, (CanvasPenEvent) -> Boolean>) {
        while (true) {
            val current = consumers.value
            if (consumers.compareAndSet(current, transform(current))) return
        }
    }
}

/**
 * The registry the canvas in this composition registers with, and the one its host delivers to.
 *
 * An instance provided by whoever owns the window, not a process-global object: a table of live
 * consumers hanging off an object outlives every window that put itself in it, hides who is
 * responsible for taking entries out, and is reachable only for a test to reset by hand
 * (`NoProcessGlobalMutableState`). The default is an empty registry nothing delivers to, which is
 * exactly right for a platform with no tablet bridge - Android's stylus arrives through Compose
 * already - and for previews.
 */
val LocalCanvasPenRegistry = androidx.compose.runtime.staticCompositionLocalOf { CanvasPenRegistry() }

/**
 * Which window the canvas in this composition belongs to, for pen routing.
 *
 * Hosts that read a tablet provide their window here; anything else shares the default, which is
 * correct for a platform with one surface (Android) and for previews.
 */
val LocalCanvasPenTarget = androidx.compose.runtime.compositionLocalOf<CanvasPenTarget> { DefaultPenTarget }

/**
 * Which surface a pen and a canvas belong to.
 *
 * Implementations are equal when they mean the same surface, because that equality is what routes
 * an event: a host makes one for its window, and the canvas inside that window registers under it.
 */
interface CanvasPenTarget

/** The target for hosts that never provide one: one surface, as on Android and in previews. */
object DefaultPenTarget : CanvasPenTarget
