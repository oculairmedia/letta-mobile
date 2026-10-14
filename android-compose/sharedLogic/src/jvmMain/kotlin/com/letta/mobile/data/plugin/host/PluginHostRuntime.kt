package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.controller.extras.MutableToolSource
import com.letta.mobile.data.plugin.InstalledPlugin
import com.letta.mobile.data.plugin.PluginActionTools
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginCommand
import com.letta.mobile.data.plugin.PluginConsent
import com.letta.mobile.data.plugin.PluginConsentDiff
import com.letta.mobile.data.plugin.PluginManifestRules
import com.letta.mobile.data.plugin.PluginPackage
import com.letta.mobile.data.plugin.PluginPackageRef
import com.letta.mobile.data.plugin.PluginPackageResult
import com.letta.mobile.data.plugin.PluginRegistryReducer
import com.letta.mobile.data.plugin.PluginRegistryState
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.PluginTransition
import com.letta.mobile.data.plugin.ZipPluginPackageReader
import com.letta.mobile.data.storage.AssetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/** What the host runtime is built from. [backend] must be built with this runtime's [PluginHostRuntime.kinds]. */
class PluginHostConfig(
    val pluginsDir: Path,
    val backend: HostCanvasBackend,
    val assets: AssetStore,
    val driverOptions: ProcessDriverOptions,
    val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
)

/** The outcome of one runtime operation. */
sealed interface PluginHostResult {
    data class Done(val plugin: InstalledPlugin?) : PluginHostResult

    /** [consentDiff] is what must be consented to when the refusal is about consent. */
    data class Refused(val message: String, val consentDiff: PluginConsentDiff? = null) : PluginHostResult
}

/** One plugin as `health()` reports it: the registry's view and its driver's, if one runs. */
data class PluginHealthSnapshot(
    val pluginId: String,
    val version: String,
    val state: PluginState,
    val consented: Boolean,
    val driver: PluginDriverState?,
    val lastFault: String?,
)

/**
 * The host's plugin runtime (letta-mobile-cdgpr.6): the registry persisted at
 * `<pluginsDir>/plugins.json` ([PluginPackageStore]), every transition decided by
 * [PluginRegistryReducer], one driver per enabled plugin ([PluginDriverHost]), and the enabled
 * plugins' agent actions published as tools into [toolSource] (register it with the live
 * `ExternalToolRegistry`).
 *
 * Consent gate: a package installs with no consent ([PluginConsent.none]); [enable] refuses until
 * [consent] covers this version's capabilities and origins, and an update that asks for more drops
 * back to needing consent. Drivers get the consented capabilities only. Operations are serialized.
 */
class PluginHostRuntime(private val config: PluginHostConfig, scope: CoroutineScope) {
    private val store = PluginPackageStore(config.pluginsDir)
    private val reader = ZipPluginPackageReader()
    private val mutex = Mutex()
    private val registry = MutableStateFlow(PluginRegistryState())

    /** The installed plugins' element kinds, for the canvas backend's validator. */
    val kinds = PluginRegistryKinds { registry.value }

    private val drivers = PluginDriverHost(config.backend, config.assets, kinds, config.driverOptions, scope)
    private val invoker = PluginToolInvoker(drivers::live)

    /** Every enabled plugin's agent tools; replaced whole on each change. */
    val toolSource = MutableToolSource(PluginActionTools.SOURCE_ID)

    val state: StateFlow<PluginRegistryState> = registry.asStateFlow()

    /** Loads the registry and starts the driver of every plugin left enabled. */
    suspend fun start() = mutex.withLock {
        registry.value = withContext(Dispatchers.IO) { store.load() }
        publishTools()
        registry.value.plugins.filter { it.state.advertises }.forEach { launch(it) }
    }

    suspend fun install(path: Path, expected: PluginPackageRef? = null): PluginHostResult =
        install(withContext(Dispatchers.IO) { Files.readAllBytes(path) }, expected)

    /** Installs a `.lcp` package (pinned to [expected] when given), or updates the installed plugin to it; a new install waits for [consent]. */
    suspend fun install(bytes: ByteArray, expected: PluginPackageRef? = null): PluginHostResult = mutex.withLock {
        val pkg = when (val read = reader.read(bytes, expected?.sha256)) {
            is PluginPackageResult.Refused -> return refused(PluginHostEvent.INSTALL, UNREAD, PluginHostResult.Refused(read.problems.joinToString("; ") { it.message }))
            is PluginPackageResult.Read -> read.pkg
        }
        val problems = PluginManifestRules.check(pkg.manifest).joinToString("; ") { "${it.path}: ${it.message}" }
        if (problems.isNotEmpty()) return refused(PluginHostEvent.INSTALL, pkg.manifest.id, PluginHostResult.Refused(problems))
        val installed = registry.value[pkg.manifest.id]
        when {
            installed == null -> installNew(pkg)
            installed.packageSha256 == pkg.ref.sha256 -> PluginHostResult.Done(installed)
            else -> update(installed, pkg)
        }
    }

    /** Records the owner's consent to [capabilities] and [origins] for the installed version. */
    suspend fun consent(pluginId: String, capabilities: Set<PluginCapability>, origins: Set<String>): PluginHostResult = mutex.withLock {
        val version = registry.value[pluginId]?.version.orEmpty()
        apply(PluginHostEvent.CONSENT, PluginCommand.RecordConsent(pluginId, PluginConsent(capabilities, origins, config.nowMs(), version)))
    }

    /** Enables [pluginId] (refused without consent covering it) and starts its driver; answers the plugin once it runs or faults. */
    suspend fun enable(pluginId: String): PluginHostResult = mutex.withLock {
        val result = apply(PluginHostEvent.ENABLE, PluginCommand.Enable(pluginId))
        if (result !is PluginHostResult.Done) return result
        registry.value[pluginId]?.takeIf { drivers.live(pluginId) == null }?.let { launch(it) }
        PluginHostResult.Done(registry.value[pluginId])
    }

    /** Stops [pluginId]'s driver and unpublishes its tools. */
    suspend fun disable(pluginId: String): PluginHostResult = mutex.withLock { disableLocked(pluginId) }

    /** Disables [pluginId], then deletes its packages and its registry entry. */
    suspend fun remove(pluginId: String): PluginHostResult = mutex.withLock {
        drivers.stop(pluginId)
        apply(PluginHostEvent.REMOVE, PluginCommand.Remove(pluginId)).also {
            if (it is PluginHostResult.Done) withContext(Dispatchers.IO) { store.delete(pluginId) }
        }
    }

    /** Disables every plugin: the runtime kill switch. */
    suspend fun disableAll() = mutex.withLock {
        registry.value.plugins.filter { it.state.advertises || drivers.live(it.id) != null }.forEach { disableLocked(it.id) }
    }

    /** Stops every driver for host shutdown; the registry keeps them enabled for the next start. */
    suspend fun close() = mutex.withLock { drivers.stopAll() }

    /** [pluginId]'s running plugin, for a live view's calls (letta-mobile-cdgpr.7); null when it is not running. */
    fun live(pluginId: String): LivePlugin? = drivers.live(pluginId)

    /** Where [plugin]'s current version is unpacked: its pages are read from here. */
    fun packageDir(plugin: InstalledPlugin): Path = store.dirOf(plugin.manifest)

    fun health(): List<PluginHealthSnapshot> = registry.value.plugins.map {
        PluginHealthSnapshot(it.id, it.version, it.state, it.consented, drivers.driverState(it.id), it.lastFault)
    }

    private suspend fun disableLocked(pluginId: String): PluginHostResult {
        drivers.stop(pluginId)
        return apply(PluginHostEvent.DISABLE, PluginCommand.Disable(pluginId))
    }

    private suspend fun installNew(pkg: PluginPackage): PluginHostResult {
        val command = PluginCommand.Install(pkg.manifest, pkg.ref.sha256, PluginConsent.none(), config.nowMs(), awaitingConsent = true)
        val next = when (val decided = decide(command)) {
            is PluginTransition.Refused -> return refused(PluginHostEvent.INSTALL, command.pluginId, refusalOf(decided))
            is PluginTransition.Applied -> decided.state
        }
        withContext(Dispatchers.IO) { store.unpack(pkg) }
        commit(next)
        PluginHostEvents.record(PluginHostEvent.INSTALL, pkg.manifest.id, "version" to pkg.manifest.version, "sha256" to pkg.ref.sha256)
        return PluginHostResult.Done(next[pkg.manifest.id])
    }

    /** Stages and completes an update at once; one asking for more than was consented comes back Installed, awaiting consent. */
    private suspend fun update(installed: InstalledPlugin, pkg: PluginPackage): PluginHostResult {
        val stage = PluginCommand.StageUpdate(pkg.manifest, pkg.ref.sha256, null, config.nowMs(), awaitingConsent = true)
        val staged = when (val decided = decide(stage)) {
            is PluginTransition.Refused -> return refused(PluginHostEvent.UPDATE, stage.pluginId, refusalOf(decided))
            is PluginTransition.Applied -> decided.state
        }
        withContext(Dispatchers.IO) { store.unpack(pkg) }
        drivers.stop(installed.id)
        val completed = (PluginRegistryReducer.reduce(staged, PluginCommand.CompleteUpdate(installed.id)) as PluginTransition.Applied).state
        commit(completed)
        val plugin = requireNotNull(completed[installed.id])
        PluginHostEvents.record(PluginHostEvent.UPDATE, plugin.id, "version" to plugin.version, "needsConsent" to (!plugin.consented).toString())
        if (plugin.state.advertises) launch(plugin)
        return PluginHostResult.Done(registry.value[plugin.id])
    }

    private suspend fun launch(plugin: InstalledPlugin) {
        val state = drivers.launch(plugin, store.dirOf(plugin.manifest)) { driver, state ->
            mutex.withLock { if (drivers.owns(plugin.id, driver)) onDriverState(plugin.id, state) }
        }
        onDriverState(plugin.id, state)
    }

    /** A driver state as registry facts: running is Active, faulted is Faulted (tools unpublished). */
    private suspend fun onDriverState(pluginId: String, state: PluginDriverState) {
        when (state) {
            is PluginDriverState.Running -> if (registry.value[pluginId]?.state != PluginState.Active) apply(PluginHostEvent.ACTIVE, PluginCommand.Activated(pluginId))
            is PluginDriverState.Restarting -> PluginHostEvents.record(PluginHostEvent.RESTART, pluginId, "reason" to state.reason, "attempt" to state.attempt.toString())
            is PluginDriverState.Faulted -> {
                drivers.forget(pluginId)
                apply(PluginHostEvent.FAULT, PluginCommand.Fault(pluginId, state.reason))
            }
            else -> Unit
        }
    }

    private suspend fun apply(event: PluginHostEvent, command: PluginCommand): PluginHostResult = when (val decided = decide(command)) {
        is PluginTransition.Refused -> refused(event, command.pluginId, refusalOf(decided))
        is PluginTransition.Applied -> {
            commit(decided.state)
            PluginHostEvents.record(event, command.pluginId)
            PluginHostResult.Done(decided.state[command.pluginId])
        }
    }

    private fun decide(command: PluginCommand): PluginTransition = PluginRegistryReducer.reduce(registry.value, command)

    private fun refusalOf(refused: PluginTransition.Refused) = PluginHostResult.Refused("${refused.refusal}: ${refused.message}", refused.consentDiff)

    private fun refused(event: PluginHostEvent, pluginId: String, refusal: PluginHostResult.Refused): PluginHostResult =
        refusal.also { PluginHostEvents.refused(event, pluginId, it) }

    private suspend fun commit(next: PluginRegistryState) {
        registry.value = next
        withContext(Dispatchers.IO) { store.save(next) }
        publishTools()
    }

    private fun publishTools() = PluginActionTools.publish(toolSource, registry.value, invoker)

    private companion object {
        /** The plugin id reported for a package that could not be read. */
        const val UNREAD = "?"
    }
}
