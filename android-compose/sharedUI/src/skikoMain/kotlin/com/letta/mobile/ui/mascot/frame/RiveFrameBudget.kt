package com.letta.mobile.ui.mascot.frame

import kotlin.math.roundToInt

// letta-mobile-ckmub.17.21: frame-budget telemetry for the live mascots.

/**
 * What the live mascots cost against the display's frame budget. Every
 * live surface reports the time its frame took to render (on the web: the Rive draw, the canvas
 * readback and the copy into Skia); over each window of [windowFrames] frames, a window in which
 * any frame went over [budgetMs] is handed to [report] as one line. A window within budget says
 * nothing. Called on the renderer's dispatcher, which is one thread.
 */
internal class RiveFrameBudget(
    private val report: (String) -> Unit,
    private val budgetMs: Double = FRAME_BUDGET_MS,
    private val windowFrames: Int = WINDOW_FRAMES,
) {
    private var frames = 0
    private var overBudget = 0
    private var totalMs = 0.0
    private var worstMs = 0.0

    /** One surface's frame took [renderMs]. */
    fun record(renderMs: Double) {
        frames++
        totalMs += renderMs
        if (renderMs > budgetMs) overBudget++
        worstMs = maxOf(worstMs, renderMs)
        if (frames < windowFrames) return
        if (overBudget > 0) report(summary())
        frames = 0
        overBudget = 0
        totalMs = 0.0
        worstMs = 0.0
    }

    private fun summary(): String =
        "[mascot] $overBudget of $frames frames over the ${tenths(budgetMs)} ms budget: " +
            "worst ${tenths(worstMs)} ms, mean ${tenths(totalMs / frames)} ms"
}

/** One 60 Hz frame. */
private const val FRAME_BUDGET_MS = 1000.0 / 60

/** About two seconds of one mascot at 60 Hz. */
private const val WINDOW_FRAMES = 120

private fun tenths(ms: Double): String = ((ms * 10).roundToInt() / 10.0).toString()
