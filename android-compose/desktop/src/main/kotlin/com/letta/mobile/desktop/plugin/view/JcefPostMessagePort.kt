package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.PluginViewPageRef
import com.letta.mobile.data.plugin.view.PostMessagePort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonPrimitive

/** Runs a script in a view's page (JCEF's `executeJavaScript` on the main frame). */
internal fun interface PageScriptRunner {
    fun run(script: String)
}

/**
 * The desktop [PostMessagePort] of one live view: the page posts through the JCEF message router
 * ([PluginViewQueryRouter] hands each query to [post]), and the host's messages reach the page by
 * running `window.__lettaViewReceive(<text>)` in it.
 *
 * The inbox is bounded: a page that posts faster than the bridge reads has its overflow refused
 * at the router (the bridge's own rate limit answers well before that in practice).
 */
internal class JcefPostMessagePort(
    private val runner: PageScriptRunner,
    capacity: Int = DEFAULT_CAPACITY,
) : PostMessagePort {
    private val inbox = Channel<String>(capacity)

    override val incoming: Flow<String> = inbox.receiveAsFlow()

    override suspend fun send(json: String) {
        runner.run(deliveryScript(json))
    }

    /** Queues [text] the page posted; false when the inbox is full or closed. */
    fun post(text: String): Boolean = inbox.trySend(text).isSuccess

    /** Ends [incoming]: nothing more is read from the page. */
    fun close() {
        inbox.close()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 64

        /** The script that hands [json] to the shim, as a JS string literal (never evaluated as code). */
        fun deliveryScript(json: String): String =
            "window.__lettaViewReceive&&window.__lettaViewReceive(" + JsonPrimitive(json).toString() + ");"
    }
}

/** How the router answers one `cefQuery` from a page. */
internal enum class PluginQueryOutcome { ACCEPTED, REFUSED_FRAME, REFUSED_FULL }

/**
 * Routes the message router's queries for one view: only the main frame showing the view's own
 * [page] may post (a framed third-party origin may not speak for the plugin), and each post goes
 * to the [port] as it came.
 */
internal class PluginViewQueryRouter(private val page: PluginViewPageRef, private val port: JcefPostMessagePort) {
    fun route(query: PluginPageQuery): PluginQueryOutcome = when {
        !query.fromPage(page) -> PluginQueryOutcome.REFUSED_FRAME
        !port.post(query.request) -> PluginQueryOutcome.REFUSED_FULL
        else -> PluginQueryOutcome.ACCEPTED
    }
}

/** One message a page posted through the router: from which frame (the main one or not, at which URL), and its text. */
internal data class PluginPageQuery(val mainFrame: Boolean, val frameUrl: String?, val request: String) {
    /** Whether it came from the main frame while that frame shows [page]. */
    fun fromPage(page: PluginViewPageRef): Boolean = mainFrame && frameUrl != null && PluginViewScheme.pageOf(frameUrl) == page
}
