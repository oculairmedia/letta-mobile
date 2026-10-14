package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.canvas.plugin.InMemoryPluginKindCatalog
import com.letta.mobile.data.canvas.plugin.PluginKindCatalog
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginKinds
import com.letta.mobile.data.plugin.catalog.HostPluginCatalog
import com.letta.mobile.data.plugin.catalog.PluginCatalog
import com.letta.mobile.data.plugin.catalog.PluginSummary
import com.letta.mobile.data.plugin.catalog.UnavailablePluginCatalog
import com.letta.mobile.data.settings.SettingValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * letta-mobile-cdgpr.8: what a shell needs to show plugin elements live, and what its Plugins page
 * runs over. It is the same on Android and desktop. The shells only bind it.
 *
 * - [supported]: the host advertises `plugins_v1` (as of the last [refresh]). The Plugins page and
 *   its Settings entry are offered only then.
 * - [catalog]: the host's catalog while it runs plugins. Otherwise it is one that lists nothing and
 *   refuses every call.
 * - [plugins]: the plugins whose views may render. These are the ones the host lists as enabled
 *   and healthy, with the manifest it sent.
 * - [kinds]: the element kinds and props schemas of [plugins], for the canvas.
 * - [liveViews]: this device's "Live plugin views" switch, persisted per device. Off, the shells
 *   provide no view host ([viewHost]), so every plugin element draws its fallback card or snapshot.
 *
 * Every signal on [hostChanges] (each change of the channel transport's state, say) runs [refresh],
 * so the model follows a reconnect or a backend switch.
 *
 * [plugins] and [kinds] are written together by one collector the model launches on [scope], so a
 * reader never sees a plugin list and a kind catalog from different host answers.
 */
class PluginViewsModel(
    private val host: HostPluginCatalog?,
    private val hostTransport: PluginViewTransport?,
    val liveViews: SettingValue<Boolean>,
    scope: CoroutineScope,
    hostChanges: Flow<Unit> = emptyFlow(),
) {
    private val unavailable = UnavailablePluginCatalog()
    private val hostRunsPlugins = MutableStateFlow(false)

    val supported: StateFlow<Boolean> = hostRunsPlugins.asStateFlow()

    val catalog: PluginCatalog get() = host?.takeIf { supported.value } ?: unavailable

    /** How live views reach the host; null while it does not run plugins. */
    val transport: PluginViewTransport? get() = hostTransport?.takeIf { supported.value }

    private val livePluginsState = MutableStateFlow<List<PluginViewPlugin>>(emptyList())
    private val kindCatalog = MutableStateFlow<PluginKindCatalog>(PluginKindCatalog.Empty)

    val plugins: StateFlow<List<PluginViewPlugin>> = livePluginsState.asStateFlow()

    /** The kinds of [plugins], following the list as it changes. */
    val kinds: PluginKindCatalog = object : PluginKindCatalog {
        override fun schemaFor(type: String, v: Int) = kindCatalog.value.schemaFor(type, v)

        override fun knows(pluginId: String) = kindCatalog.value.knows(pluginId)
    }

    init {
        scope.launch { hostChanges.collect { refresh() } }
        scope.launch {
            combine(host?.plugins ?: flowOf(emptyList()), hostRunsPlugins) { listed, runs -> if (runs) livePlugins(listed) else emptyList() }
                .collect { publish(it) }
        }
    }

    private fun publish(live: List<PluginViewPlugin>) {
        kindCatalog.value = kindsOf(live)
        livePluginsState.value = live
    }

    val liveViewsEnabled: StateFlow<Boolean> get() = liveViews.current

    fun setLiveViewsEnabled(enabled: Boolean) = liveViews.set(enabled)

    /** Re-reads whether the host runs plugins and, if it does, its list. Answers [supported]. */
    suspend fun refresh(): Boolean {
        val runs = host?.supported == true
        hostRunsPlugins.value = runs
        if (runs) host?.refresh()
        return runs
    }

    companion object {
        /** [viewHost] while live views are [enabled] on this device, else null: the board draws every element's card. */
        fun <H : Any> viewHost(viewHost: H, enabled: Boolean): H? = viewHost.takeIf { enabled }

        /** The plugins of [listed] whose views may render: enabled, healthy, and with a manifest. */
        fun livePlugins(listed: List<PluginSummary>): List<PluginViewPlugin> = listed
            .filter { it.enabled && it.healthy }
            .mapNotNull { summary -> summary.manifest?.let(::PluginViewPlugin) }

        /** The kinds [plugins] declare. A kind that cannot be registered (or is declared twice) is left out, not the catalog. */
        fun kindsOf(plugins: List<PluginViewPlugin>): PluginKindCatalog {
            val specs = plugins.flatMap { PluginKinds.specsOf(it.manifest) }
                .filter { it.problems().isEmpty() }
                .distinctBy { it.elementType to it.schemaVersion }
            return if (specs.isEmpty()) PluginKindCatalog.Empty else InMemoryPluginKindCatalog(specs)
        }

        private val PluginSummary.healthy: Boolean
            get() = health == PluginHealth.Ok || health == PluginHealth.Unknown
    }
}
