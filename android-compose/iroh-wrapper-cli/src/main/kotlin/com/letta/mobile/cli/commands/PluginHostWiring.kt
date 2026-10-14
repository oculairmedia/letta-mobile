package com.letta.mobile.cli.commands

import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.plugin.host.LateBoundPluginKinds
import com.letta.mobile.data.plugin.host.PluginHostConfig
import com.letta.mobile.data.plugin.host.PluginHostEvent
import com.letta.mobile.data.plugin.host.PluginHostEvents
import com.letta.mobile.data.plugin.host.PluginHostRuntime
import com.letta.mobile.data.plugin.host.ProcessDriverOptions
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.plugin.api.HostInfo
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/** The serve-iroh plugin flags (letta-mobile-cdgpr.6): `--plugins=on|off` and `--plugins-dir`. */
internal data class PluginHostFlags(val mode: String = ON, val dir: String? = null) {
    val off: Boolean get() = mode.equals(OFF, ignoreCase = true)

    /** `--plugins-dir`, else `~/.letta/plugins`. */
    fun root(home: String = System.getProperty("user.home") ?: "."): Path = dir?.let(Path::of) ?: Path.of(home, ".letta", "plugins")

    companion object {
        const val ON = "on"
        const val OFF = "off"
    }
}

/** The canvas side the plugin host places through: the relay's backend (built with [kinds]) and asset store. */
internal class PluginHostCanvas(val backend: HostCanvasBackend, val assets: AssetStore, val kinds: LateBoundPluginKinds)

/**
 * Starts the wrapper's plugin host after the canvas relay: binds the installed kinds into the
 * backend's catalog, registers the plugins' tool source with the live [registry] (so enabled
 * plugins' actions reach agents as tools), loads `plugins.json` and starts the enabled drivers.
 * `--plugins=off` is the global kill switch: nothing is constructed, no tool is published, and
 * null is returned.
 */
internal suspend fun startPluginHost(
    flags: PluginHostFlags,
    canvas: PluginHostCanvas,
    registry: ExternalToolRegistry,
    scope: CoroutineScope,
): PluginHostRuntime? {
    if (flags.off) {
        println("[iroh-app-server] Plugins: OFF (--plugins=off)")
        PluginHostEvents.record(PluginHostEvent.KILL_SWITCH, PluginHostEvents.ALL, "scope" to "global", "plugins" to "0")
        return null
    }
    val root = flags.root()
    val hostInfo = HostInfo("letta-iroh-wrapper", PluginHostRuntime::class.java.`package`?.implementationVersion ?: "dev")
    val runtime = PluginHostRuntime(PluginHostConfig(root, canvas.backend, canvas.assets, ProcessDriverOptions(hostInfo)), scope)
    canvas.kinds.target = runtime.kinds
    registry.addSource(runtime.toolSource)
    runtime.start()
    val health = runtime.health()
    println("[iroh-app-server] Plugins: ON (dir: $root, installed: ${health.size}, running: ${health.count { it.driver != null }})")
    return runtime
}
