package com.letta.mobile.data.plugin.catalog

/**
 * A [PluginCatalog] on a connected host. It knows whether that host runs
 * plugins at all ([supported]) and can reload its list ([refresh]). The admin_rpc catalog is one;
 * [com.letta.mobile.data.plugin.view.PluginViewsModel] reads it.
 */
interface HostPluginCatalog : PluginCatalog {
    /** Whether the connected host advertises `plugins_v1`. Read it after connecting, because capabilities arrive with auth. */
    val supported: Boolean

    /** Reloads [plugins] from the host. */
    suspend fun refresh(): PluginCatalogResult<Unit>
}
