package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginRuntime

/** Portable, deterministic runtime/contract selection without invoking or importing an executor. */
internal class RuntimeProviderRegistry<T>(registrations: List<RuntimeProviderRegistration<T>>) {
    private val registrations = registrations.toList()

    init {
        require(this.registrations.all { it.id.value.isNotBlank() }) { "driver provider IDs must not be blank" }
        val duplicateIds = this.registrations.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "duplicate driver provider IDs: ${duplicateIds.map { it.value }.sorted().joinToString()}"
        }
    }

    fun resolve(manifest: PluginManifest): Result<T> {
        val kind = PluginRuntime.kindOf(manifest.runtime)
        val version = manifest.contract.version
        val matches = registrations.filter { it.runtimeKind == kind && version in it.contractVersions }
        return if (matches.size == 1) {
            Result.success(matches.single().provider)
        } else {
            val reason = if (matches.isEmpty()) "unsupported" else "ambiguous"
            val ids = matches.ifEmpty { registrations }.map { it.id.value }.sorted().joinToString()
            val message = "$reason driver provider for ${manifest.id}: runtime $kind contract $version; providers [$ids]"
            Result.failure(IllegalStateException(message))
        }
    }
}
