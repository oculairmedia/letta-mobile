package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginManifest

/**
 * Explicit JVM executor inventory using portable metadata selection. Empty hosts support no execution,
 * and overlapping runtime/contract matches never choose a winner. Resolution invokes no factory.
 */
class PluginDriverProviders(providers: List<PluginDriverProvider>) {
    private val registry = RuntimeProviderRegistry(providers.map { provider ->
        RuntimeProviderRegistration(provider, PluginId(provider.id), provider.runtimeKind, provider.contractVersions)
    })

    /** Resolves runtime kind and contract major without invoking any provider factory. */
    fun resolve(manifest: PluginManifest): Result<PluginDriverProvider> = registry.resolve(manifest)
}
