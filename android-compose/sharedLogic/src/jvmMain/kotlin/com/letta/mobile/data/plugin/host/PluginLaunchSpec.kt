package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginSecrets
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/** Installed package inputs supplied to driver providers; execution policy belongs to the selected provider. */
class PluginLaunchSpec(
    /** Installed manifest, including the requested runtime and contract major. */
    val manifest: PluginManifest,
    /** Unpacked package root; an executor must enforce its runtime's path policy. */
    val packageDir: Path,
    /** Resolved settings passed to the plugin's initialization. */
    val settings: JsonObject,
    /** Secret container used for substitution and output scrubbing. */
    val secrets: PluginSecrets,
    /** Only owner-consented capabilities; executors intersect these with the manifest. */
    val consentedCapabilities: Set<PluginCapability>,
) {
    /** A launch failure may contain environment/settings details; never persist secret-bearing output. */
    internal fun failureReason(failure: Exception): String =
        secrets.scrubber().scrub(failure.message ?: "driver launch failed")
}
