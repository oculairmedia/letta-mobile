package com.letta.mobile.desktop.runtime

/**
 * What a restarted bundled child does to the desktop's node: a running generation still holds the old child's port and
 * bearer token, so it is restarted (through [handOff]'s bridge lease) whichever backend the window shows. Gating this on
 * the window being in local mode left a stale generation behind for the next return to LOCAL. With no generation
 * running there is no attached shell to bridge, but the restart still runs: it waits out a generation that a dial is
 * starting right now (possibly over the old child) and ends it, and is a no-op otherwise.
 */
internal class DesktopRuntimeRestartResponder(
    private val nodeRunning: () -> Boolean,
    private val handOff: suspend (reconnect: () -> Unit) -> Unit,
    private val restartNode: () -> Unit,
) {
    /** Called once for each restart the bundled runtime's supervisor reports. */
    suspend fun onRuntimeRestarted() {
        if (nodeRunning()) handOff(restartNode) else restartNode()
    }
}
