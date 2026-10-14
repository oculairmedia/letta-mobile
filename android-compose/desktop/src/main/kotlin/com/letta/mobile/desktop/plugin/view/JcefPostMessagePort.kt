package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.ui.canvas.plugin.PluginPageChannel
import kotlinx.serialization.json.JsonPrimitive

/** Runs a script in a view's page (JCEF's `executeJavaScript` on the main frame). */
internal fun interface PageScriptRunner {
    fun run(script: String)
}

/**
 * The JCEF end of one live view's [PluginPageChannel]: the page posts through the JCEF message
 * router ([PluginViewQueryRouter] hands each query to [post]), and what the bridge sends reaches
 * the page by running `window.__lettaViewReceive(<text>)` in it ([deliver]).
 *
 * The channel's page inbox is bounded: a page that posts faster than the bridge reads has its
 * overflow refused at the router (the bridge's own rate limit answers well before that in practice).
 */
internal class JcefPostMessagePort(
    private val channel: PluginPageChannel,
    private val runner: PageScriptRunner,
) {
    /** Hands every message the bridge sends to the page, in order, until the channel is closed. */
    suspend fun deliver() {
        channel.toPage.collect { json -> runner.run(deliveryScript(json)) }
    }

    /** Queues [text] the page posted; false when the inbox is full or closed. */
    fun post(text: String): Boolean = channel.postFromPage(text)

    /** Ends both directions: nothing more is read from or sent to the page. */
    fun close() {
        channel.close()
    }

    companion object {
        /** The script that hands [json] to the shim, as a JS string literal (never evaluated as code). */
        fun deliveryScript(json: String): String =
            "window.__lettaViewReceive&&window.__lettaViewReceive(" + JsonPrimitive(json).toString() + ");"
    }
}

/** How the router answers one `cefQuery` from a page. */
internal enum class PluginQueryOutcome { ACCEPTED, REFUSED_FRAME, REFUSED_FULL }

/**
 * Routes the message router's queries for one view: only the main frame showing the view's own
 * page ([pageUrl]) may post (a framed third-party origin may not speak for the plugin), and each
 * post goes to the [port] as it came.
 */
internal class PluginViewQueryRouter(private val pageUrl: String, private val port: JcefPostMessagePort) {
    fun route(query: PluginPageQuery): PluginQueryOutcome = when {
        !query.fromPage(pageUrl) -> PluginQueryOutcome.REFUSED_FRAME
        !port.post(query.request) -> PluginQueryOutcome.REFUSED_FULL
        else -> PluginQueryOutcome.ACCEPTED
    }
}

/** One message a page posted through the router: from which frame (the main one or not, at which URL), and its text. */
internal data class PluginPageQuery(val mainFrame: Boolean, val frameUrl: String?, val request: String) {
    /** Whether it came from the main frame while that frame shows the page at [pageUrl]. */
    fun fromPage(pageUrl: String): Boolean = mainFrame && frameUrl == pageUrl
}
