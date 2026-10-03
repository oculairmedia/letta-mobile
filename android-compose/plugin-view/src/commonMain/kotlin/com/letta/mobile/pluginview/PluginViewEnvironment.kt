package com.letta.mobile.pluginview

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.view.PluginViewConsent
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.ViewHostContext
import com.letta.mobile.data.plugin.view.ViewLinkPolicy
import com.letta.mobile.data.plugin.view.ViewPlatform
import kotlinx.serialization.json.JsonObject

/**
 * What a shell gives its live views on one board: which canvas they belong to, how they reach the
 * host ([transport]; [PluginViewTransport.Unavailable] keeps every element on its fallback card),
 * the link policy and consent the bridge asks, and the first-use device permissions the person
 * granted each plugin on this device ([granted], by plugin id).
 */
@Immutable
data class PluginViewEnvironment(
    val canvasId: String,
    val platform: ViewPlatform,
    val transport: PluginViewTransport = PluginViewTransport.Unavailable,
    val links: ViewLinkPolicy = ViewLinkPolicy.DenyAll,
    val consent: PluginViewConsent = PluginViewConsent.DenyAll,
    val granted: Map<String, Set<PluginCapability>> = emptyMap(),
) {
    /** Whether a live view can load at all; without a host every element keeps its card. */
    val online: Boolean get() = transport !== PluginViewTransport.Unavailable
}

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
