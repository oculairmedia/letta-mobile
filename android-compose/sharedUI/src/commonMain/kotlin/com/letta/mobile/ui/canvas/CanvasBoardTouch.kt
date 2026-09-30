package com.letta.mobile.ui.canvas

import kotlinx.coroutines.flow.MutableStateFlow

/** One sample of a finger, in the window's logical coordinates. */
data class CanvasBoardTouchSample(
    val contact: Int,
    val phase: Phase,
    val x: Float,
    val y: Float,
) {
    enum class Phase { DOWN, MOVE, UP, CANCEL }
}

/** What the board did with a finger sample. */
sealed interface CanvasBoardTouchResult {
    /** Not on the board. The platform scrolls or clicks as it does everywhere else. */
    data object Ignored : CanvasBoardTouchResult

    /** The board took it: a two-finger pan or pinch, or a finger it is still placing. */
    data object Consumed : CanvasBoardTouchResult

    /** A short press on the board. The platform posts one click at the finger. */
    data class Tap(val x: Float, val y: Float) : CanvasBoardTouchResult

    /**
     * One finger became the mouse. The platform holds the button down where the finger
     * landed ([x], [y]) and drags it to where the finger is now ([toX], [toY]).
     */
    data class Press(val x: Float, val y: Float, val toX: Float, val toY: Float) : CanvasBoardTouchResult

    /** The held finger moved. The platform drags the held button here. */
    data class Drag(val x: Float, val y: Float) : CanvasBoardTouchResult

    /** The held finger lifted. The platform releases the button where it last dragged. */
    data object Release : CanvasBoardTouchResult
}

/**
 * The seam between a platform's fingers and the canvas.
 *
 * Desktop fingers arrive from the pen bridge, not as [androidx.compose.ui.input.pointer.PointerType.Touch],
 * so the board's own pinch never sees them. The platform offers each sample here first. One
 * finger is the mouse, two fingers pan and pinch; anything the board does not take goes back
 * to ordinary scrolling.
 */
class CanvasBoardTouchRegistry {
    private val consumers = MutableStateFlow<Map<CanvasPenTarget, (CanvasBoardTouchSample) -> CanvasBoardTouchResult>>(emptyMap())

    fun register(target: CanvasPenTarget, consumer: (CanvasBoardTouchSample) -> CanvasBoardTouchResult): () -> Unit {
        update { it + (target to consumer) }
        return { update { current -> if (current[target] === consumer) current - target else current } }
    }

    fun deliver(target: CanvasPenTarget, sample: CanvasBoardTouchSample): CanvasBoardTouchResult =
        consumers.value[target]?.invoke(sample) ?: CanvasBoardTouchResult.Ignored

    private fun update(
        transform: (
            Map<CanvasPenTarget, (CanvasBoardTouchSample) -> CanvasBoardTouchResult>,
        ) -> Map<CanvasPenTarget, (CanvasBoardTouchSample) -> CanvasBoardTouchResult>,
    ) {
        while (true) {
            val current = consumers.value
            if (consumers.compareAndSet(current, transform(current))) return
        }
    }
}

val LocalCanvasBoardTouchRegistry = androidx.compose.runtime.staticCompositionLocalOf { CanvasBoardTouchRegistry() }
