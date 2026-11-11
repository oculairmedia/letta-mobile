package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A window's board services and backend lifetime, independent of platform retention.
 * Hosts serialize lifecycle calls on their window dispatcher; [factory] constructs services without I/O.
 */
open class CanvasBoardOwner(private val factory: () -> CanvasBoardSessions) {
    private val current = MutableStateFlow(factory())
    private val retirement = mutableSetOf<() -> Unit>()
    private var backend: LettaConfig? = null
    private var resolved = false
    private var closed = false
    private var retiring = false

    /** The current backend's table and registry. */
    val boards = current.asStateFlow()

    /** Uses the shell's config id; the first loaded config adopts startup state without retiring pages. */
    fun updateBackend(config: LettaConfig?) {
        if (closed) return
        if (!resolved) {
            if (config == null) return
            backend = config
            resolved = true
            return
        }
        if (backend?.id == config?.id) return
        backend = config
        retiring = true
        retireCurrent()
        val replacement = factory()
        retiring = false
        current.value = replacement
    }

    /** Retires pages before discarding their table; a finished owner cannot be reopened. */
    fun close() {
        if (closed) return
        closed = true
        retireCurrent()
    }

    internal fun onRetire(boards: CanvasBoardSessions, action: () -> Unit): () -> Unit {
        if (closed || retiring || current.value !== boards) {
            action()
            return {}
        }
        retirement += action
        return { retirement -= action }
    }

    private fun retireCurrent() {
        val actions = retirement.toList()
        retirement.clear()
        actions.forEach { it() }
        current.value.clear()
    }
}
