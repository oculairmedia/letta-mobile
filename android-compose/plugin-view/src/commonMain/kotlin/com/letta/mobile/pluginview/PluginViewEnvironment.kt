package com.letta.mobile.pluginview

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.view.PluginViewConsent
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.ViewLinkPolicy
import com.letta.mobile.data.plugin.view.ViewPlatform

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
 * An installed plugin as the client knows it from the host's catalog. It lives in sharedLogic
 * (letta-mobile-cdgpr.8) so the shared [com.letta.mobile.data.plugin.view.PluginViewsModel] can
 * hand the shells their list; this name stays for the renderer.
 */
typealias PluginViewPlugin = com.letta.mobile.data.plugin.view.PluginViewPlugin
