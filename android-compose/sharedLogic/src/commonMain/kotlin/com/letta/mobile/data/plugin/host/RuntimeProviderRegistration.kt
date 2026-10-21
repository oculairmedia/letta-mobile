package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.model.PluginId

/** Captured executor metadata shared across hosts; [provider] remains an opaque host-owned binding. */
internal class RuntimeProviderRegistration<T>(
    val provider: T,
    val id: PluginId,
    val runtimeKind: String,
    contractVersions: Set<Int>,
) {
    val contractVersions: Set<Int> = contractVersions.toSet()
}
