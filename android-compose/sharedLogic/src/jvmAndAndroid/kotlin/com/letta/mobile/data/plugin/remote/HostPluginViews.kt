package com.letta.mobile.data.plugin.remote

import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.settings.SettingValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * letta-mobile-cdgpr.8: [PluginPackageUploader] over the canvas relay. The package goes into the
 * host's asset store outside any canvas, pinned by the sha256 the Plugins page showed, and
 * `plugins.install` then installs it from there.
 */
class RelayPluginPackageUploader(private val relay: CanvasRelayClient) : PluginPackageUploader {
    override suspend fun upload(bytes: ByteArray, sha256: String): String = relay.uploadAsset(PLUGIN_PACKAGE_MEDIA_TYPE, bytes, sha256)

    companion object {
        /** A `.lcp` package is a zip. */
        const val PLUGIN_PACKAGE_MEDIA_TYPE = "application/zip"
    }
}

/**
 * The host side of a [PluginViewsModel]: its admin_rpc [link], and [changes], each signal of which
 * refreshes the model. [onEachChange] turns any state flow into that signal.
 */
class PluginHostConnection(val link: PluginAdminLink, val changes: Flow<Unit>) {
    companion object {
        /** A connection refreshed on every value of [state] (the channel transport's state, say). */
        fun <T> onEachChange(link: PluginAdminLink, state: Flow<T>): PluginHostConnection =
            PluginHostConnection(link, state.map { })
    }
}

/**
 * The shells' [PluginViewsModel] on [host]. It uses the admin_rpc catalog and view transport,
 * uploads packages through [relay], and keeps this device's [liveViews] switch.
 */
fun hostPluginViewsModel(
    host: PluginHostConnection,
    relay: CanvasRelayClient,
    liveViews: SettingValue<Boolean>,
    scope: CoroutineScope,
): PluginViewsModel = PluginViewsModel(
    host = AdminRpcPluginCatalog(host.link, RelayPluginPackageUploader(relay)),
    hostTransport = AdminRpcPluginViewTransport(host.link),
    liveViews = liveViews,
    scope = scope,
    hostChanges = host.changes,
)
