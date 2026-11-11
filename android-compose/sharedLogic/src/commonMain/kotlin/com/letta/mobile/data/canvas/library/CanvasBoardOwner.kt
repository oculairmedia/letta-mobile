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
    private val identity = MutableStateFlow(CanvasBoardContext(current.value, 0L))
    private val retirement = mutableSetOf<() -> Unit>()
    private var backend: LettaConfig? = null
    private var resolved = false
    private var closed = false
    private var retiring = false

    /** The current backend's table and registry. */
    val boards = current.asStateFlow()

    /** Atomic table and generation snapshot for retained page identities in host composition. */
    val context = identity.asStateFlow()

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
        // Publish services before the host snapshot so fresh page holders bind the replacement table.
        current.value = replacement
        identity.value = CanvasBoardContext(replacement, identity.value.generation + 1L)
    }

    /** Retires pages before discarding their table; a finished owner cannot be reopened. */
    fun close() {
        if (closed) return
        closed = true
        retireCurrent()
    }

    internal fun onRetire(boards: CanvasBoardSessions, action: () -> Unit): () -> Unit {
        if (!canRetainPage(boards)) {
            action()
            return {}
        }
        retirement += action
        return { retirement -= action }
    }

    private fun canRetainPage(boards: CanvasBoardSessions): Boolean =
        !closed && !retiring && current.value === boards

    private fun retireCurrent() {
        val actions = retirement.toList()
        retirement.clear()
        actions.forEach { it() }
        current.value.clear()
    }
}
