package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.plugin.PluginKindCatalog
import com.letta.mobile.data.plugin.InstalledPlugin
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginSecrets
import com.letta.mobile.data.storage.AssetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The drivers of the enabled plugins (letta-mobile-cdgpr.6), one per plugin id. Each gets a
 * [ProcessPluginSpec] from its manifest and the CONSENTED capabilities only, and [HostLcpHandlers]
 * placing through a [PlacementCompiler] on the board of its last agent caller. Every driver state
 * change after start is passed to [onState], which the runtime turns into registry transitions.
 */
class PluginDriverHost(
    private val canvas: HostCanvasBackend,
    private val assets: AssetStore,
    private val kinds: PluginKindCatalog,
    private val options: ProcessDriverOptions,
    private val scope: CoroutineScope,
) {
    private class Entry(val live: LivePlugin, var watcher: Job? = null)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val limits = PluginRateLimits()
    private val quota = PluginAssetQuota()
    val refusals = PluginRefusals()

    fun live(pluginId: String): LivePlugin? = entries[pluginId]?.live

    fun driverState(pluginId: String): PluginDriverState? = live(pluginId)?.driver?.state?.value

    /**
     * Starts [plugin] from [packageDir] and suspends until it runs or gives up; answers that state.
     * Later states go to [onState] (on [scope]) while the driver is still this host's.
     */
    suspend fun launch(plugin: InstalledPlugin, packageDir: Path, onState: suspend (PluginDriver, PluginDriverState) -> Unit): PluginDriverState {
        val id = plugin.id
        val board = CallerBoard(canvas)
        val secrets = PluginSecrets()
        val handlers = HostLcpHandlers(id, PlacementCompiler(id, kinds, canvas, board, refusals), assets, secrets.scrubber(), limits, quota, refusals)
        val spec = ProcessPluginSpec(plugin.manifest, packageDir, defaultSettings(plugin.manifest), secrets, plugin.consent.capabilities)
        val driver = ProcessPluginDriver(spec, handlers, scope, options.copy(onStderr = { PluginHostEvents.record(PluginHostEvent.STDERR, id, "line" to it) }))
        val entry = Entry(LivePlugin(driver, handlers, board))
        entries[id] = entry
        val started = driver.start()
        entry.watcher = scope.launch { driver.state.collect { if (entries[id] === entry) onState(driver, it) } }
        return started
    }

    /** Whether [driver] is still the one running [pluginId]. */
    fun owns(pluginId: String, driver: PluginDriver): Boolean = entries[pluginId]?.live?.driver === driver

    /** Forgets [pluginId]'s driver without stopping it (it ended on its own). */
    fun forget(pluginId: String) {
        entries.remove(pluginId)?.watcher?.cancel()
    }

    /** Stops [pluginId]'s driver, if one runs; its state changes no longer reach the runtime. */
    suspend fun stop(pluginId: String) {
        val entry = entries.remove(pluginId) ?: return
        entry.watcher?.cancelAndJoin()
        entry.live.driver.stop()
    }

    /** Stops every driver (host shutdown); the registry is left as it is, so they start again next time. */
    suspend fun stopAll() {
        entries.keys.toList().forEach { stop(it) }
    }

    private companion object {
        /**
         * The manifest's setting defaults. TODO(letta-mobile-cdgpr.14): a host settings and
         * secrets store; until then a plugin runs on its defaults with no secrets.
         */
        fun defaultSettings(manifest: PluginManifest): JsonObject =
            JsonObject(manifest.settings.mapNotNull { (name, field) -> field.default?.let { name to it } }.toMap())
    }
}
