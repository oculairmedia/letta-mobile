package com.letta.mobile.data.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where an installed plugin is in its life (plan section 6.2): installed but off, enabled (its tools
 * advertised), active (its driver running), faulted (its actions answer with an error, its elements
 * stay as fallback cards), or updating (a new version staged, the old one kept for rollback).
 */
@Serializable
sealed interface PluginState {
    @Serializable @SerialName("installed")
    data object Installed : PluginState

    @Serializable @SerialName("enabled")
    data object Enabled : PluginState

    @Serializable @SerialName("active")
    data object Active : PluginState

    @Serializable @SerialName("faulted")
    data class Faulted(val reason: String) : PluginState

    @Serializable @SerialName("updating")
    data object Updating : PluginState

    /** Whether a plugin in this state has its agent tools advertised. */
    val advertises: Boolean get() = this == Enabled || this == Active
}

/** What the plugin last said about itself (`health()` / `plugin.health`). */
@Serializable
sealed interface PluginHealth {
    @Serializable @SerialName("unknown")
    data object Unknown : PluginHealth

    @Serializable @SerialName("ok")
    data object Ok : PluginHealth

    @Serializable @SerialName("degraded")
    data class Degraded(val reason: String) : PluginHealth

    @Serializable @SerialName("failed")
    data class Failed(val reason: String) : PluginHealth
}

/** One installed version of a plugin: its manifest, the package hash it was installed from, and the consent it holds. */
@Serializable
data class PluginVersionRecord(
    val manifest: PluginManifest,
    val packageSha256: String,
    val consent: PluginConsent,
    val installedAtEpochMs: Long,
)

/** A new version staged by an update, and the state the plugin returns to when the update completes or fails. */
@Serializable
data class PendingUpdate(
    val record: PluginVersionRecord,
    val resumeState: PluginState,
    val settingsSummary: PluginSettingsSummary? = null,
)

/**
 * One plugin in the host's registry (`plugins.json`, plan section 6.1). The current version is
 * [current]; [previousVersion] is kept for rollback, [pendingUpdate] while an update is staged.
 * No secret value is ever held here: only [secretsStatus].
 */
@Serializable
data class InstalledPlugin(
    val current: PluginVersionRecord,
    val state: PluginState = PluginState.Installed,
    val settingsSummary: PluginSettingsSummary = PluginSettingsSummary(),
    val secretsStatus: Map<String, SecretState> = emptyMap(),
    val health: PluginHealth = PluginHealth.Unknown,
    val previousVersion: PluginVersionRecord? = null,
    val pendingUpdate: PendingUpdate? = null,
    val lastFault: String? = null,
) {
    val manifest: PluginManifest get() = current.manifest
    val id: String get() = manifest.id
    val version: String get() = manifest.version
    val consent: PluginConsent get() = current.consent
    val packageSha256: String get() = current.packageSha256

    /** Whether the plugin's agent tools are advertised: enabled or active, or updating from one of those. */
    val advertised: Boolean get() = state.advertises || (state == PluginState.Updating && pendingUpdate?.resumeState?.advertises == true)

    /** Whether everything the plugin needs to run is set: required settings and required secrets. */
    val ready: Boolean
        get() = settingsSummary.complete && manifest.secrets.filter { it.required }.all { secretsStatus[it.name] == SecretState.SET }
}

/** The whole registry: every installed plugin by id, in install order. */
@Serializable
data class PluginRegistryState(val plugins: List<InstalledPlugin> = emptyList()) {
    operator fun get(pluginId: String): InstalledPlugin? = plugins.firstOrNull { it.id == pluginId }

    /** The plugins whose agent tools are advertised. */
    val advertised: List<InstalledPlugin> get() = plugins.filter { it.advertised }

    internal fun with(plugin: InstalledPlugin): PluginRegistryState =
        if (this[plugin.id] == null) copy(plugins = plugins + plugin) else copy(plugins = plugins.map { if (it.id == plugin.id) plugin else it })

    internal fun without(pluginId: String): PluginRegistryState = copy(plugins = plugins.filterNot { it.id == pluginId })

    /** The registry as `plugins.json`. */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            classDiscriminator = "kind"
            encodeDefaults = true
            explicitNulls = false
        }

        fun decode(text: String): PluginRegistryState = json.decodeFromString(serializer(), text)
    }
}
