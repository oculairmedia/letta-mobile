package com.letta.mobile.desktop.plugin.view

import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRequestContext
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Component
import javax.swing.SwingUtilities

/** What one view's browser is wired to: the page it serves (and its policy), its router, and where faults go. */
internal class JcefViewWiring(
    val server: PluginPageServer,
    val queries: PluginViewQueryRouter,
    val work: PluginViewWork,
    val onFault: (String) -> Unit,
) {
    val gate: JcefRequestGate = JcefRequestGate(server.policy)
}

/** The native side of one live view: the component the board shows, scripts run in its page, and how to release it. */
internal interface PluginBrowserHandle : PageScriptRunner {
    val component: Component

    /** Closes the browser and frees what it holds; called once, after the bridge's teardown. */
    fun dispose()
}

/** Opens one view's browser on the process's browser runtime [T] (a `CefApp`; a fake in tests). */
internal fun interface PluginBrowserFactory<T> {
    fun open(app: T, wiring: JcefViewWiring): PluginBrowserHandle
}

/**
 * One live view's JCEF browser (plan section 7.3): its own [CefClient] and an in-memory
 * [CefRequestContext] (no cookies, storage or cache shared with any other view or the app), a
 * windowed browser whose AWT component the board embeds, the shared page policy ([JcefRequestGate])
 * enforced on every navigation and resource, popups, downloads and the context menu refused, and a
 * [CefMessageRouter] whose queries reach the view's port through [PluginViewQueryRouter].
 */
internal class JcefPluginBrowser private constructor(
    private val client: CefClient,
    private val context: CefRequestContext,
    private val router: CefMessageRouter,
    private val browser: CefBrowser,
    private val pageUrl: String,
) : PluginBrowserHandle {
    override val component: Component get() = browser.uiComponent

    override fun run(script: String) {
        browser.executeJavaScript(script, pageUrl, 0)
    }

    override fun dispose() {
        val release = {
            browser.close(true)
            client.removeMessageRouter(router)
            router.dispose()
            client.dispose()
            context.dispose()
        }
        if (SwingUtilities.isEventDispatchThread()) release() else SwingUtilities.invokeLater(release)
    }

    companion object : PluginBrowserFactory<CefApp> {
        override fun open(app: CefApp, wiring: JcefViewWiring): JcefPluginBrowser {
            val client = app.createClient()
            val config = CefMessageRouter.CefMessageRouterConfig(PluginPageShim.QUERY_FUNCTION, PluginPageShim.CANCEL_FUNCTION)
            val router = CefMessageRouter.create(config)
            router.addHandler(JcefQueryHandler(wiring.queries).asRouterHandler(), true)
            client.addMessageRouter(router)
            client.addRequestHandler(RenderFaults(wiring))
            client.addLifeSpanHandler(JcefNoPopups)
            client.addDownloadHandler(JcefNoDownloads)
            client.addContextMenuHandler(JcefNoContextMenu)
            client.addLoadHandler(LoadFaults(wiring))
            val context = isolatedContext(ResourceHandler(wiring))
            val browser = client.createBrowser(wiring.server.url, false, false, context)
            return JcefPluginBrowser(client, context, router, browser, wiring.server.url)
        }

        /**
         * The view's own in-memory request context; every request made in it, navigations included,
         * goes through [resources]. A download is never handed to CEF's default handling.
         */
        private fun isolatedContext(resources: CefResourceRequestHandler): CefRequestContext =
            CefRequestContext.createContext { _, _, _, _, isDownload, _, disableDefaultHandling ->
                disableDefaultHandling?.set(isDownload)
                resources
            }
    }
}

/** A renderer that dies hands its element back to the card. */
private class RenderFaults(private val wiring: JcefViewWiring) : CefRequestHandlerAdapter() {
    override fun onRenderProcessTerminated(
        browser: CefBrowser?,
        status: CefRequestHandler.TerminationStatus?,
        errorCode: Int,
        errorString: String?,
    ) {
        wiring.onFault("the page stopped (${status?.name?.lowercase() ?: "terminated"})")
    }
}

/**
 * Navigations are resource loads of the main frame or a subframe, so one check covers them and every
 * other resource: a request the [JcefRequestGate] refuses is cancelled before it is sent. The view's
 * own page comes from the [PluginPageServer], never the network.
 */
private class ResourceHandler(private val wiring: JcefViewWiring) : CefResourceRequestHandlerAdapter() {
    override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean =
        wiring.gate.cancels(request?.url.orEmpty(), mainFrame = request?.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME)

    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler? =
        if (wiring.gate.serves(request?.url.orEmpty())) PluginPageResourceHandler(wiring.server, wiring.work) else null
}

/**
 * A page that loads with anything but 200 hands its element back to the card at once. One that fails
 * before it commits never sends `view.ready`, and the session's ready watch catches it.
 */
private class LoadFaults(private val wiring: JcefViewWiring) : CefLoadHandlerAdapter() {
    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
        if (frame?.isMain != true || httpStatusCode == PluginPageResponse.STATUS_OK) return
        wiring.onFault(wiring.server.failure ?: "the page answered $httpStatusCode")
    }
}
