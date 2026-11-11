package com.letta.mobile.data.canvas.library

/** Atomic identity of one generation of window-owned board services. */
data class CanvasBoardContext(
    /** Services belonging to this generation. */
    val boards: CanvasBoardSessions,
    /** Monotonic page-identity namespace within the retained window. */
    val generation: Long,
)
