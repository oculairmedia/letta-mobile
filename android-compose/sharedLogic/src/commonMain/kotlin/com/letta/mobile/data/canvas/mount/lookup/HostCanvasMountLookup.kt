package com.letta.mobile.data.canvas.mount.lookup

// Plain string ids on purpose: this package sits between the canvas package and the mount store, and importing the
// shared id types here would join an existing package cycle.

/** What the host-owned canvas mount of a caller's own conversation says, for a tool call that names no canvas. */
sealed interface HostCanvasMount {
    /** No binding and no agent default: the conversation's own canvas is current. */
    data object None : HostCanvasMount

    /** The conversation is mounted on the canvas [canvasId] (its binding, else its agent's default). */
    data class Mounted(val canvasId: String) : HostCanvasMount

    /** The mount could not be read; a tool must not guess a board and asks for an explicit canvas instead. */
    data object Unavailable : HostCanvasMount
}

/**
 * Reads, and can never change, the canvas mounted for the agent and conversation the runtime stamped on a call;
 * nothing in a tool's input reaches it. Agents cannot mount canvases (owner decision O4).
 */
fun interface HostCanvasMountLookup {
    /** The mount of the conversation [conversationId] of the agent [agentId]. */
    suspend fun mounted(agentId: String, conversationId: String): HostCanvasMount

    /** Lookups that need no host mount store. */
    companion object {
        /** Hosts without canvas mounts: every conversation shows its own canvas. */
        val None: HostCanvasMountLookup = HostCanvasMountLookup { _, _ -> HostCanvasMount.None }
    }
}
