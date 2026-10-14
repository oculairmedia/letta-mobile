package com.letta.mobile.data.plugin.host

import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * Where one plugin's driver is in its life. [Faulted] and [Stopped] are terminal for the driver
 * instance: a faulted plugin is restarted only by a new driver (an explicit re-enable).
 */
sealed interface PluginDriverState {
    /** Spawning and handshaking (`plugin.initialize`, then `plugin.activate`). */
    data object Starting : PluginDriverState

    /** Activated; actions and events are delivered. [pid] is the plugin process's, when there is one. */
    data class Running(val pid: Long?) : PluginDriverState

    /** The plugin crashed or failed its health check; [attempt] counts crashes in the current window. */
    data class Restarting(val reason: String, val attempt: Int) : PluginDriverState

    /** Too many crashes in the window, or a configuration that cannot start; no further restarts. */
    data class Faulted(val reason: String) : PluginDriverState

    /** Deactivated on request. */
    data class Stopped(val reason: String) : PluginDriverState

    val terminal: Boolean get() = this is Faulted || this is Stopped
}

/**
 * One running plugin, whatever its runtime (`process` today; `service` and `jvm` later): the host
 * runtime keeps a map of these by plugin id without knowing how each one runs.
 */
interface PluginDriver {
    val pluginId: String

    val state: StateFlow<PluginDriverState>

    /** Starts the plugin and suspends until it is [PluginDriverState.Running] or terminal; answers that state. */
    suspend fun start(): PluginDriverState

    /** Runs an action; throws `LcpCallException` (`CLOSED`, `DEADLINE_EXCEEDED`, ...) when the plugin cannot answer. */
    suspend fun invoke(call: ActionCall): ActionResult

    suspend fun elementEvent(event: ElementEvent)

    /** Deactivates and stops the plugin; the driver ends [PluginDriverState.Stopped]. */
    suspend fun stop()
}
