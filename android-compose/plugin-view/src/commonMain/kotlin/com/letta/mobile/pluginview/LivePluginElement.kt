package com.letta.mobile.pluginview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.intl.Locale
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewBridgeServices
import com.letta.mobile.data.plugin.view.ViewElement
import com.letta.mobile.data.plugin.view.ViewHostContext
import com.letta.mobile.ui.canvas.plugin.PluginElementChrome
import com.letta.mobile.ui.canvas.plugin.PluginElementView
import com.letta.mobile.ui.canvas.plugin.PluginFallbackCard
import com.letta.mobile.ui.canvas.plugin.PluginLiveCard
import com.letta.mobile.ui.canvas.plugin.PluginPageChannel
import com.letta.mobile.ui.canvas.plugin.PluginViewHost
import com.letta.mobile.ui.canvas.plugin.PluginViewSession
import com.letta.mobile.ui.canvas.plugin.PluginViewSlot

/** The page one element kind of one installed plugin version is shown live with. */
@Immutable
internal data class LivePage(val plugin: PluginViewPlugin, val kind: String, val pageId: String) {
    val elementType: String get() = plugin.manifest.elementType(kind)
}

/**
 * The live renderer of every plugin element kind with a page (letta-mobile-s416w.13): the element
 * drawn by the provided [PluginViewHost] inside the card's frame, or by its fallback card when no
 * host is provided, the host is offline, or the element is not of this exact kind (a registered
 * prefix also matches longer kind names).
 */
@Composable
internal fun LivePluginElement(page: LivePage, environment: PluginViewEnvironment, view: PluginElementView, chrome: PluginElementChrome) {
    if (view.element.type != page.elementType || !environment.online) {
        PluginFallbackCard(view, chrome)
        return
    }
    PluginViewSlot(view, chrome) { host -> HostedPluginElement(host, page, environment, view, chrome) }
}

@Composable
private fun HostedPluginElement(
    host: PluginViewHost,
    page: LivePage,
    environment: PluginViewEnvironment,
    view: PluginElementView,
    chrome: PluginElementChrome,
) {
    val element = ViewElement.of(view.element)
    val context = rememberViewContext(page, environment, element)
    val uriHandler = LocalUriHandler.current
    val spec = remember(page, environment.canvasId, element.id) {
        PluginViewSpec.of(page.plugin.manifest, page.pageId, environment.canvasId, element)
    }
    if (spec == null) {
        PluginFallbackCard(view, chrome)
        return
    }
    val bridgeHost = remember(spec) { CanvasViewBridgeHost(context, uriHandler::openUri) }
    val session = remember(spec, environment) { sessionOf(spec, bridgeHost, environment) }
    SideEffect { bridgeHost.current = context }
    LaunchedEffect(session, element) { session.bridge.elementChanged(element) }
    LaunchedEffect(session, context.theme, context.cssVariables, context.locale) { session.bridge.pushContext() }
    PluginLiveCard(view, chrome) { modifier -> host.View(session, modifier, chrome.fail) }
}

private fun sessionOf(spec: PluginViewSpec, bridgeHost: CanvasViewBridgeHost, environment: PluginViewEnvironment): PluginViewSession {
    val channel = PluginPageChannel()
    val services = ViewBridgeServices(bridgeHost, environment.transport, environment.links, environment.consent)
    return PluginViewSession(ViewBridge(spec, channel, services), channel, environment.transport, environment.granted[spec.pluginId].orEmpty())
}

/** What `host.context` says right now: the board's theme and colours, the element, the plugin's public settings. */
@Composable
private fun rememberViewContext(page: LivePage, environment: PluginViewEnvironment, element: ViewElement): ViewHostContext {
    val colors = MaterialTheme.colorScheme
    val locale = Locale.current.toLanguageTag()
    return remember(colors, locale, element, page, environment.platform) {
        ViewHostContext(
            theme = ViewCssVariables.theme(colors),
            cssVariables = ViewCssVariables.of(colors),
            element = element,
            settingsPublic = page.plugin.publicSettings,
            displayMode = PluginDisplayMode.INLINE,
            locale = locale,
            platform = environment.platform,
        )
    }
}
