package com.letta.mobile.data.runtime.host

/**
 * What a restarted bundled runtime does to the host's own node: a running node generation still holds the old
 * runtime's port and bearer token, so it is restarted (through [handOff]'s bridge lease) whichever backend the window
 * shows; gating this on the window being in local mode leaves a stale generation behind for the next return to it.
 * With no generation running there is no attached shell to bridge, but the restart still runs: it waits out a
 * generation a dial is starting right now (possibly over the old runtime) and ends it, and is a no-op otherwise.
 *
 * @param nodeRunning whether a node generation is running now.
 * @param handOff runs its argument while a short-lived bridge lease keeps the fresh runtime from being stopped.
 * @param restartNode ends the node's current generation; attached shells redial.
 */
class RuntimeRestartResponder(
    private val nodeRunning: () -> Boolean,
    private val handOff: suspend (reconnect: () -> Unit) -> Unit,
    private val restartNode: () -> Unit,
) {
    /** Called once for each restart the runtime's supervisor reports. */
    suspend fun onRuntimeRestarted() {
        if (nodeRunning()) handOff(restartNode) else restartNode()
    }
}
