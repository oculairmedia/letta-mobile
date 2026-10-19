package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.plugin.PluginKindCatalog
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.InstalledPlugin
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginSecrets
import com.letta.mobile.data.storage.AssetStore
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The drivers of the enabled plugins, one per plugin id. Each gets a
 * [PluginLaunchSpec] from its manifest and the CONSENTED capabilities only, and [HostLcpHandlers]
 * placing through a [PlacementCompiler] on the board of the call each emit names ([CallerBoard]).
 * Every driver state change after start is passed to [onState], which the runtime turns into
 * registry transitions.
 */
class PluginDriverHost(
    private val canvas: HostCanvasBackend,
    private val assets: AssetStore,
    private val kinds: PluginKindCatalog,
    private val providers: PluginDriverProviders,
    private val scope: CoroutineScope,
    private val quota: PluginAssetQuota = PluginAssetQuota(),
) {
    private class Entry(val live: LivePlugin, var watcher: Job? = null)

    private val entries = ConcurrentHashMap<PluginId, Entry>()
    private val limits = PluginRateLimits()
    val refusals = PluginRefusals()

    fun live(pluginId: PluginId): LivePlugin? = entries[pluginId]?.live

    fun driverState(pluginId: PluginId): PluginDriverState? = live(pluginId)?.driver?.state?.value

    /**
     * Starts [plugin] from [packageDir] and suspends until it runs or gives up; answers that state.
     * Later states go to [onState] (on [scope]) while the driver is still this host's. A plugin whose
     * recorded consent is not for this very version and everything it asks for (a hand-edited or
     * stale `plugins.json`) is never spawned: it answers Faulted at once.
     * Ordinary factory/start failures answer a scrubbed Faulted state after cleanup. Cancellation
     * and fatal errors propagate after stopping any partially created driver.
     */
    suspend fun launch(plugin: InstalledPlugin, packageDir: Path, onState: suspend (PluginDriver, PluginDriverState) -> Unit): PluginDriverState {
        val id = plugin.pluginId
        if (!plugin.consented) return PluginDriverState.Faulted("consent does not cover $id ${plugin.version}; consent again to run it")
        val provider = providers.resolve(plugin.manifest).getOrElse {
            return PluginDriverState.Faulted(it.message.orEmpty())
        }
        val board = CallerBoard(canvas)
        val secrets = PluginSecrets()
        val handlers = HostLcpHandlers(id, PlacementCompiler(id, kinds, canvas, board, refusals), assets, secrets.scrubber(), limits, quota, refusals)
        val spec = PluginLaunchSpec(plugin.manifest, packageDir, defaultSettings(plugin.manifest), secrets, plugin.consent.capabilities)
        var driver: PluginDriver? = null
        return runCatching {
            val created = provider.create(spec, handlers, scope) {
                PluginHostEvents.record(PluginHostEvent.STDERR, id, "line" to it)
            }
            driver = created
            startDriver(id, LivePlugin(created, handlers, board), onState)
        }.getOrElse { failure ->
            discardLaunch(id, driver)
            if (failure is CancellationException || failure !is Exception) throw failure
            PluginDriverState.Faulted(spec.failureReason(failure))
        }
    }

    private suspend fun startDriver(
        id: PluginId,
        live: LivePlugin,
        onState: suspend (PluginDriver, PluginDriverState) -> Unit,
    ): PluginDriverState {
        val driver = live.driver
        val entry = Entry(live)
        entries[id] = entry
        val started = driver.start()
        currentCoroutineContext().ensureActive()
        // The watcher ends by itself after a terminal state: [forget] runs inside it, so cancelling it
        // there would cancel the runtime's own Faulted transition halfway (letta-mobile-cdgpr.10).
        entry.watcher = scope.launch {
            driver.state.first { state ->
                if (entries[id] === entry) onState(driver, state)
                state.terminal
            }
        }
        return started
    }

    private suspend fun discardLaunch(id: PluginId, driver: PluginDriver?) = withContext(NonCancellable) {
        val entry = entries[id]?.takeIf { it.live.driver === driver }
        if (entry != null) {
            entries.remove(id, entry)
            entry.watcher?.cancelAndJoin()
        }
        // Cleanup errors must not replace the original launch failure or cancellation. A fatal
        // runtime error still escapes; a provider's stop must release resources in its own finally.
        val stopFailure = runCatching { driver?.stop() }.exceptionOrNull()
        if (stopFailure != null && stopFailure !is Exception) throw stopFailure
    }

    /** Whether [driver] is still the one running [pluginId]. */
    fun owns(pluginId: PluginId, driver: PluginDriver): Boolean = entries[pluginId]?.live?.driver === driver

    /** Forgets [pluginId]'s driver without stopping it (it ended on its own; its watcher ends with it). */
    fun forget(pluginId: PluginId) {
        entries.remove(pluginId)
    }

    /** Stops [pluginId]'s driver, if one runs; its state changes no longer reach the runtime. */
    suspend fun stop(pluginId: PluginId) {
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
         * The manifest's setting defaults. The host currently supplies no settings overrides
         * or secrets; persisted settings and secret injection belong at this launch boundary.
         */
        fun defaultSettings(manifest: PluginManifest): JsonObject =
            JsonObject(manifest.settings.mapNotNull { (name, field) -> field.default?.let { name to it } }.toMap())
    }
}
