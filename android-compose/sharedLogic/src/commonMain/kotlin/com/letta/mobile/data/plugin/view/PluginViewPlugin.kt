package com.letta.mobile.data.plugin.view

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.plugin.PluginManifest
import kotlinx.serialization.json.JsonObject

/**
 * An installed plugin as the client knows it from the host's catalog: its [manifest] and the
 * values of its settings. Pages only ever see the public part ([publicSettings]).
 */
@Immutable
data class PluginViewPlugin(val manifest: PluginManifest, val settings: JsonObject = JsonObject(emptyMap())) {
    /** The settings a page may see: only the fields the manifest declares, never secrets. */
    val publicSettings: JsonObject get() = ViewHostContext.publicSettings(manifest, settings)

    /** The page of each element kind that declares one the manifest has, by kind. */
    val livePages: Map<String, String>
        get() = manifest.elements.mapNotNull { (kind, spec) -> spec.page?.takeIf { it in manifest.pages }?.let { kind to it } }.toMap()
}
