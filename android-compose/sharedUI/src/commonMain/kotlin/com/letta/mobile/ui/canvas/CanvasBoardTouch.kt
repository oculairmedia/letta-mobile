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

    /** The board took it: a pan or a pinch. */
    data object Consumed : CanvasBoardTouchResult

    /** A short press on the board. The platform posts one click at the finger. */
    data class Tap(val x: Float, val y: Float) : CanvasBoardTouchResult
}

/**
 * The seam between a platform's fingers and the canvas.
 *
 * Desktop fingers arrive from the pen bridge, not as [androidx.compose.ui.input.pointer.PointerType.Touch],
 * so the board's own pinch and one-finger pan never see them. The platform offers each sample
 * here first. The board pans in both axes and pinches; anything it does not take goes back to
 * ordinary scrolling.
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
