package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId

/** What the host-owned canvas mount of a caller's own conversation says, for a tool call that names no canvas. */
sealed interface HostCanvasMount {
    /** No binding and no agent default: the conversation's own canvas is current. */
    data object None : HostCanvasMount

    /** The conversation is mounted on [canvas] (its binding, else its agent's default). */
    data class Mounted(val canvas: CanvasId) : HostCanvasMount

    /** The mount could not be read; a tool must not guess a board and asks for an explicit canvas instead. */
    data object Unavailable : HostCanvasMount
}

/**
 * Reads, and can never change, the canvas mounted for the agent and conversation the runtime stamped on a call
 * ([HostCanvasCaller]); nothing in a tool's input reaches it. Agents cannot mount canvases (owner decision O4).
 */
fun interface HostCanvasMountLookup {
    /** The mount of [caller]'s own conversation. */
    suspend fun mounted(caller: HostCanvasCaller): HostCanvasMount

    /** Lookups that need no host mount store. */
    companion object {
        /** Hosts without canvas mounts: every conversation shows its own canvas. */
        val None: HostCanvasMountLookup = HostCanvasMountLookup { HostCanvasMount.None }
    }
}
