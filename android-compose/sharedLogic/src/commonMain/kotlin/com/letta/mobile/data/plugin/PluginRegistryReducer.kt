package com.letta.mobile.data.plugin

/** A change to the registry, as the management API (.30) and the drivers (.28) ask for it. */
sealed interface PluginCommand {
    val pluginId: String

    /** Installs [manifest] from the package hashing to [packageSha256], with the owner's [consent]. */
    data class Install(
        val manifest: PluginManifest,
        val packageSha256: String,
        val consent: PluginConsent,
        val nowEpochMs: Long,
        /** Null for a plugin with no settings set yet: every required field without a default is missing. */
        val settingsSummary: PluginSettingsSummary? = null,
    ) : PluginCommand {
        override val pluginId: String get() = manifest.id
    }

    /** Stages [manifest] as the next version; [consent] is needed when it asks for more than the installed one. */
    data class StageUpdate(
        val manifest: PluginManifest,
        val packageSha256: String,
        val consent: PluginConsent?,
        val nowEpochMs: Long,
        val settingsSummary: PluginSettingsSummary? = null,
    ) : PluginCommand {
        override val pluginId: String get() = manifest.id
    }

    /** The staged version started: it becomes current, the old one is kept for [Rollback]. */
    data class CompleteUpdate(override val pluginId: String) : PluginCommand

    /** The staged version failed to start: it is dropped and the installed one resumes (rollback on failure). */
    data class FailUpdate(override val pluginId: String, val reason: String) : PluginCommand

    /** Back to the version before the last completed update. */
    data class Rollback(override val pluginId: String) : PluginCommand

    data class Enable(override val pluginId: String) : PluginCommand

    data class Disable(override val pluginId: String) : PluginCommand

    data class Remove(override val pluginId: String) : PluginCommand

    data class RecordConsent(override val pluginId: String, val consent: PluginConsent) : PluginCommand

    data class SettingsSet(override val pluginId: String, val summary: PluginSettingsSummary) : PluginCommand

    data class SecretsSet(override val pluginId: String, val status: Map<String, SecretState>) : PluginCommand

    /** The driver started (on the first action or event, or at enable for jvm). */
    data class Activated(override val pluginId: String) : PluginCommand

    /** The driver stopped while idle; the plugin stays enabled. */
    data class Stopped(override val pluginId: String) : PluginCommand

    /** The driver crashed past its restarts. */
    data class Fault(override val pluginId: String, val reason: String) : PluginCommand

    data class HealthReported(override val pluginId: String, val health: PluginHealth) : PluginCommand
}

enum class PluginRefusal {
    UNKNOWN_PLUGIN,
    ALREADY_INSTALLED,
    BAD_HASH,
    UNSUPPORTED_CONTRACT,
    CONSENT_REQUIRED,
    TOOL_COLLISION,
    NOT_NEWER,
    UPDATE_IN_PROGRESS,
    NO_UPDATE_PENDING,
    NO_PREVIOUS_VERSION,
    NOT_READY,
    INVALID_STATE,
}

/** The outcome of one [PluginCommand]. */
sealed interface PluginTransition {
    data class Applied(val state: PluginRegistryState) : PluginTransition

    /** [consentDiff] says what must be consented to when [refusal] is CONSENT_REQUIRED. */
    data class Refused(val refusal: PluginRefusal, val message: String, val consentDiff: PluginConsentDiff? = null) : PluginTransition
}

/**
 * The registry's pure transitions (plan section 6.1, letta-mobile-s416w.24): install, update with
 * rollback, enable, disable, remove, consent, settings, secrets and the driver's lifecycle. File I/O
 * and drivers are elsewhere (.28/.30); this decides only what the next state is, or why there is
 * none. Packages are pinned by hash: a version is installed from one hash only, an update must
 * be a newer version, and an update that asks for more (capabilities, origins, another runtime)
 * needs fresh consent.
 */
object PluginRegistryReducer {
    fun reduce(state: PluginRegistryState, command: PluginCommand): PluginTransition = when (command) {
        is PluginCommand.Install -> PluginInstalls.install(state, command)
        is PluginCommand.StageUpdate -> PluginInstalls.stageUpdate(state, command)
        else -> state[command.pluginId]?.let { plugin -> reduceInstalled(state, plugin, command) }
            ?: refuse(PluginRefusal.UNKNOWN_PLUGIN, "no plugin '${command.pluginId}' is installed")
    }

    private fun reduceInstalled(state: PluginRegistryState, plugin: InstalledPlugin, command: PluginCommand): PluginTransition = when (command) {
        is PluginCommand.CompleteUpdate -> PluginInstalls.completeUpdate(state, plugin)
        is PluginCommand.FailUpdate -> PluginInstalls.failUpdate(state, plugin, command.reason)
        is PluginCommand.Rollback -> PluginInstalls.rollback(state, plugin)
        is PluginCommand.Remove -> updating(plugin) ?: PluginTransition.Applied(state.without(plugin.id))
        is PluginCommand.RecordConsent -> recordConsent(state, plugin, command.consent)
        is PluginCommand.SettingsSet -> applied(state, plugin.copy(settingsSummary = command.summary))
        is PluginCommand.SecretsSet -> applied(state, plugin.copy(secretsStatus = plugin.manifest.secrets.associate { it.name to (command.status[it.name] ?: SecretState.UNSET) }))
        is PluginCommand.HealthReported -> applied(state, plugin.copy(health = command.health))
        else -> PluginLifecycle.reduce(state, plugin, command)
    }

    private fun recordConsent(state: PluginRegistryState, plugin: InstalledPlugin, consent: PluginConsent): PluginTransition {
        val diff = PluginConsentDiff.between(consent, plugin.manifest)
        if (!diff.isEmpty || consent.forVersion != plugin.version) {
            return refuse(PluginRefusal.CONSENT_REQUIRED, "the consent does not cover ${plugin.id} ${plugin.version}", diff)
        }
        return applied(state, plugin.copy(current = plugin.current.copy(consent = consent)))
    }

    internal fun applied(state: PluginRegistryState, plugin: InstalledPlugin): PluginTransition = PluginTransition.Applied(state.with(plugin))

    internal fun refuse(refusal: PluginRefusal, message: String, diff: PluginConsentDiff? = null): PluginTransition =
        PluginTransition.Refused(refusal, message, diff)

    /** The refusal of anything but finishing the update while [plugin] is updating; null otherwise. */
    internal fun updating(plugin: InstalledPlugin): PluginTransition? =
        refuse(PluginRefusal.UPDATE_IN_PROGRESS, "${plugin.id} is updating; wait for it to finish").takeIf { plugin.state == PluginState.Updating }
}

/** Enable, disable and the driver's lifecycle: the state machine of plan section 6.2. */
internal object PluginLifecycle {
    fun reduce(state: PluginRegistryState, plugin: InstalledPlugin, command: PluginCommand): PluginTransition {
        PluginRegistryReducer.updating(plugin)?.let { return it }
        if (command is PluginCommand.Enable) return enable(state, plugin)
        val next = nextState(plugin.state, command)
            ?: return PluginRegistryReducer.refuse(PluginRefusal.INVALID_STATE, "${plugin.id} is ${plugin.state}; it cannot take $command")
        val fault = (next as? PluginState.Faulted)?.reason ?: plugin.lastFault
        return PluginRegistryReducer.applied(state, plugin.copy(state = next, lastFault = fault))
    }

    /** The state [command] moves a plugin in [current] to, or null when it cannot happen there. */
    private fun nextState(current: PluginState, command: PluginCommand): PluginState? = when (command) {
        is PluginCommand.Disable -> PluginState.Installed
        is PluginCommand.Activated -> PluginState.Active.takeIf { current.advertises }
        is PluginCommand.Stopped -> PluginState.Enabled.takeIf { current.advertises }
        is PluginCommand.Fault -> PluginState.Faulted(command.reason).takeIf { current.advertises }
        else -> null
    }

    private fun enable(state: PluginRegistryState, plugin: InstalledPlugin): PluginTransition = when {
        plugin.state.advertises -> PluginTransition.Applied(state)
        !plugin.ready -> PluginRegistryReducer.refuse(PluginRefusal.NOT_READY, "${plugin.id} needs its required settings and secrets before it can be enabled")
        !plugin.consent.covers(plugin.manifest) -> PluginRegistryReducer.refuse(
            PluginRefusal.CONSENT_REQUIRED, "${plugin.id} asks for more than was consented to", PluginConsentDiff.between(plugin.consent, plugin.manifest),
        )
        else -> PluginRegistryReducer.applied(state, plugin.copy(state = PluginState.Enabled))
    }
}
