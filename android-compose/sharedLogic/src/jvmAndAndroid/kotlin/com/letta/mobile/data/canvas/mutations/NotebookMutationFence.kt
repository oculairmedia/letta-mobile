package com.letta.mobile.data.canvas.mutations

/** Serializes native callback execution with withdrawal before an outer authority lock is released. */
internal class NotebookMutationFence {
    private var active = true

    /** Withdrawn callbacks return without mutation; an executing callback holds this monitor. */
    @Synchronized
    fun <T> runIfActive(action: () -> T): T? = if (active) action() else null

    /** Wait for an executing callback, then permanently refuse any callback still queued. */
    @Synchronized
    fun withdraw() {
        active = false
    }
}
