package com.letta.mobile.pluginview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.ui.canvas.plugin.LocalPluginElementRenderers
import com.letta.mobile.ui.canvas.plugin.LocalPluginViewHost
import com.letta.mobile.ui.canvas.plugin.PluginElementRenderers
import com.letta.mobile.ui.canvas.plugin.PluginViewHost

/**
 * How a shell turns plugin elements into live views (plan section 7.3, letta-mobile-s416w.13).
 * Nothing here names a plugin: every element kind whose plugin declares a page gets the same live
 * renderer, parameterised by the plugin, its version and the page.
 */
object PluginViewHosts {
    /**
     * [renderers] with a live renderer for every element kind of [plugins] that has a page. An
     * element is still drawn by its fallback card while no host is provided, the host is offline,
     * or its page cannot be shown.
     */
    fun register(
        renderers: PluginElementRenderers,
        plugins: List<PluginViewPlugin>,
        environment: PluginViewEnvironment,
    ): PluginElementRenderers =
        plugins.fold(renderers) { registered, plugin ->
            plugin.livePages.entries.fold(registered) { withKinds, (kind, pageId) ->
                val binding = LiveBinding(LivePage(plugin, kind, pageId), environment)
                withKinds.register(binding.page.elementType) { view, chrome -> LivePluginElement(binding, view, chrome) }
            }
        }
}

/**
 * [content] with [host] as the board's live-view host and a live renderer for every element kind
 * of [plugins] that has a page: the one call a shell makes around its canvas.
 */
@Composable
fun ProvidePluginViews(
    host: PluginViewHost?,
    environment: PluginViewEnvironment,
    plugins: List<PluginViewPlugin>,
    content: @Composable () -> Unit,
) {
    val base = LocalPluginElementRenderers.current
    // Worked out once per input: a new set on every recomposition would recompose every element.
    val renderers = remember(base, plugins, environment) { PluginViewHosts.register(base, plugins, environment) }
    CompositionLocalProvider(
        LocalPluginViewHost provides host,
        LocalPluginElementRenderers provides renderers,
        content = content,
    )
}

/**
 * letta-mobile-cdgpr.8: [content] with live views from [model], the one call a shell makes around
 * its canvas. The model supplies the host's enabled plugins and its view transport. When this
 * device's "Live plugin views" switch is off, no [host] is provided, so every element draws its
 * fallback card or snapshot. Without a model, [content] is drawn as it is, every element its card.
 */
@Composable
fun ProvidePluginViews(
    model: PluginViewsModel?,
    host: PluginViewHost,
    canvasId: String,
    platform: ViewPlatform,
    content: @Composable () -> Unit,
) {
    if (model == null) return content()
    LaunchedEffect(model) { model.refresh() }
    val enabled by model.liveViewsEnabled.collectAsState()
    val supported by model.supported.collectAsState()
    val plugins by model.plugins.collectAsState()
    val environment = remember(model, canvasId, platform, supported) {
        PluginViewEnvironment(canvasId, platform, transport = model.transport ?: PluginViewTransport.Unavailable)
    }
    ProvidePluginViews(PluginViewsModel.viewHost(host, enabled), environment, plugins, content)
}
