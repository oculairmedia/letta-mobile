package com.letta.mobile.data.plugin.view

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The page side of a live view, as the platform host (Android WebView, desktop JCEF, wasm iframe)
 * exposes it: raw JSON-RPC text in both directions. The host injects [LcpViewShim] before the page,
 * delivers what the page posts on [incoming], and hands what [send] gets to the shim's receive hook.
 */
interface PostMessagePort {
    /** Delivers [json] to the page. */
    suspend fun send(json: String)

    /** Everything the page posts, in order. */
    val incoming: Flow<String>
}

/** One `view.action` on its way to the host's plugin: the view's own plugin, canvas and element. */
data class PluginViewActionCall(
    val pluginId: String,
    val canvasId: String,
    val elementId: String,
    val action: String,
    val input: JsonObject,
)

/** What came of a [PluginViewActionCall]. */
sealed interface PluginViewActionOutcome {
    /** The action ran: [text] for people, [structured] for the page. */
    data class Done(val text: String, val structured: JsonElement? = null) : PluginViewActionOutcome

    /** The plugin ran the action and refused or failed it; [message] is safe to show (the host scrubbed it). */
    data class Failed(val message: String) : PluginViewActionOutcome

    /** The host cannot be reached, or the plugin is disabled there. */
    data class Unavailable(val reason: String) : PluginViewActionOutcome
}

/**
 * How a client reaches the host for a live view (plan section 7.2): the page's HTML and the view's
 * actions. The `meridian/plugin-view/1` ALPN implements it (letta-mobile-s416w.32), and so can a
 * loopback in desktop direct mode; [Unavailable] is the offline transport.
 */
interface PluginViewTransport {
    /** The page's HTML from the package of [pluginId] at [version]; throws [PluginViewUnavailableException] when it cannot. */
    suspend fun readPage(pluginId: String, version: String, pageId: String): ByteArray

    suspend fun action(call: PluginViewActionCall): PluginViewActionOutcome

    /** No host: pages cannot load and actions answer [PluginViewActionOutcome.Unavailable]. */
    object Unavailable : PluginViewTransport {
        override suspend fun readPage(pluginId: String, version: String, pageId: String): ByteArray =
            throw PluginViewUnavailableException("no host connection for $pluginId@$version page $pageId")

        override suspend fun action(call: PluginViewActionCall): PluginViewActionOutcome =
            PluginViewActionOutcome.Unavailable("no host connection")
    }
}

class PluginViewUnavailableException(message: String) : Exception(message)
