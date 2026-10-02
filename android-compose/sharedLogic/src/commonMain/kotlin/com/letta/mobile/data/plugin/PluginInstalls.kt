package com.letta.mobile.data.plugin

import com.letta.mobile.data.plugin.PluginRegistryReducer.applied
import com.letta.mobile.data.plugin.PluginRegistryReducer.refuse
import kotlinx.serialization.json.JsonObject

/**
 * Install, update and rollback (plan sections 6.1 and 6.4): which version is current, which is
 * staged, which is kept, and what each needs: a well-formed package hash, a supported contract,
 * consent covering what the version asks for, and agent tool names no other plugin has.
 */
internal object PluginInstalls {
    fun install(state: PluginRegistryState, command: PluginCommand.Install): PluginTransition {
        val manifest = command.manifest
        if (state[manifest.id] != null) return refuse(PluginRefusal.ALREADY_INSTALLED, "${manifest.id} is installed; update it instead")
        packageProblem(state, manifest, command.packageSha256)?.let { return it }
        val diff = PluginConsentDiff.between(command.consent, manifest)
        if (!diff.isEmpty || command.consent.forVersion != manifest.version) {
            return refuse(PluginRefusal.CONSENT_REQUIRED, "consent to what ${manifest.id} ${manifest.version} asks for before installing it", diff)
        }
        val record = PluginVersionRecord(manifest, command.packageSha256, command.consent, command.nowEpochMs)
        val settings = command.settingsSummary ?: PluginSettingsSummary.of(manifest, JsonObject(emptyMap()))
        return applied(state, InstalledPlugin(record, settingsSummary = settings, secretsStatus = secretsFor(manifest, emptyMap())))
    }

    fun stageUpdate(state: PluginRegistryState, command: PluginCommand.StageUpdate): PluginTransition {
        val manifest = command.manifest
        val plugin = state[manifest.id] ?: return refuse(PluginRefusal.UNKNOWN_PLUGIN, "${manifest.id} is not installed; install it first")
        PluginRegistryReducer.updating(plugin)?.let { return it }
        packageProblem(state, manifest, command.packageSha256)?.let { return it }
        versionProblem(plugin, manifest)?.let { return it }
        val consent = consentForUpdate(plugin, manifest, command.consent) ?: return refuse(
            PluginRefusal.CONSENT_REQUIRED, "${manifest.id} ${manifest.version} asks for more than was consented to; consent again",
            PluginConsentDiff.forUpdate(plugin.manifest, plugin.consent, manifest),
        )
        val record = PluginVersionRecord(manifest, command.packageSha256, consent, command.nowEpochMs)
        val pending = PendingUpdate(record, resumeState = plugin.state, settingsSummary = command.settingsSummary)
        return applied(state, plugin.copy(state = PluginState.Updating, pendingUpdate = pending))
    }

    fun completeUpdate(state: PluginRegistryState, plugin: InstalledPlugin): PluginTransition {
        val pending = plugin.pendingUpdate ?: return refuse(PluginRefusal.NO_UPDATE_PENDING, "${plugin.id} has no update staged")
        val updated = switchTo(plugin, pending.record, pending.settingsSummary ?: plugin.settingsSummary)
        return applied(state, resume(updated.copy(previousVersion = plugin.current, pendingUpdate = null), pending.resumeState))
    }

    fun failUpdate(state: PluginRegistryState, plugin: InstalledPlugin, reason: String): PluginTransition {
        val pending = plugin.pendingUpdate ?: return refuse(PluginRefusal.NO_UPDATE_PENDING, "${plugin.id} has no update staged")
        val fault = "update to ${pending.record.manifest.version} failed: $reason"
        return applied(state, resume(plugin.copy(pendingUpdate = null, lastFault = fault), pending.resumeState))
    }

    fun rollback(state: PluginRegistryState, plugin: InstalledPlugin): PluginTransition {
        PluginRegistryReducer.updating(plugin)?.let { return it }
        val previous = plugin.previousVersion ?: return refuse(PluginRefusal.NO_PREVIOUS_VERSION, "${plugin.id} has no earlier version to go back to")
        val rolledBack = switchTo(plugin, previous, plugin.settingsSummary).copy(previousVersion = null)
        return applied(state, resume(rolledBack, plugin.state))
    }

    private fun packageProblem(state: PluginRegistryState, manifest: PluginManifest, sha256: String): PluginTransition? = when {
        PluginPackageRef.of(sha256) == null -> refuse(PluginRefusal.BAD_HASH, "a package is installed by its sha256 (64 lowercase hex characters)")
        manifest.contract.version !in PluginManifestRules.SUPPORTED_CONTRACT_VERSIONS ->
            refuse(PluginRefusal.UNSUPPORTED_CONTRACT, "contract version ${manifest.contract.version} is not supported here")
        else -> collision(state, manifest)
    }

    /** A refusal when another installed plugin already offers one of [manifest]'s agent tools. */
    private fun collision(state: PluginRegistryState, manifest: PluginManifest): PluginTransition? {
        val mine = PluginActionTools.definitions(manifest).map { it.name }.toSet()
        val taken = state.plugins.filter { it.id != manifest.id }.flatMap { other ->
            PluginActionTools.definitions(other.manifest).filter { it.name in mine }.map { "${it.name} (${other.id})" }
        }
        return refuse(PluginRefusal.TOOL_COLLISION, "agent tools already offered by another plugin: ${taken.joinToString()}").takeIf { taken.isNotEmpty() }
    }

    /** Versions are pinned to the package they came from: an update is a newer version, never the same one again. */
    private fun versionProblem(plugin: InstalledPlugin, manifest: PluginManifest): PluginTransition? {
        val newer = isNewer(PluginSemVer.parse(plugin.version), PluginSemVer.parse(manifest.version))
        return refuse(PluginRefusal.NOT_NEWER, "${manifest.version} is not newer than the installed ${plugin.version}; publish a new version").takeIf { !newer }
    }

    private fun isNewer(current: PluginSemVer?, next: PluginSemVer?): Boolean {
        if (current == null || next == null) return false
        return next > current
    }

    /** The consent the update runs under, or null when it asks for more than the owner agreed to. */
    private fun consentForUpdate(plugin: InstalledPlugin, manifest: PluginManifest, fresh: PluginConsent?): PluginConsent? {
        if (fresh != null) return fresh.takeIf { it.covers(manifest) && it.forVersion == manifest.version }
        val diff = PluginConsentDiff.forUpdate(plugin.manifest, plugin.consent, manifest)
        return plugin.consent.copy(forVersion = manifest.version).takeIf { diff.isEmpty }
    }

    private fun switchTo(plugin: InstalledPlugin, record: PluginVersionRecord, settings: PluginSettingsSummary): InstalledPlugin = plugin.copy(
        current = record,
        settingsSummary = settings,
        secretsStatus = secretsFor(record.manifest, plugin.secretsStatus),
    )

    /** [plugin] back in [before]'s state after a version change: enabled again when it was and still can be. */
    private fun resume(plugin: InstalledPlugin, before: PluginState): InstalledPlugin {
        val wasOn = before.advertises || before is PluginState.Faulted
        return plugin.copy(state = if (wasOn && plugin.ready) PluginState.Enabled else PluginState.Installed)
    }

    private fun secretsFor(manifest: PluginManifest, known: Map<String, SecretState>): Map<String, SecretState> =
        manifest.secrets.associate { it.name to (known[it.name] ?: SecretState.UNSET) }
}
