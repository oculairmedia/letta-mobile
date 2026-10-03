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
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.Component
import javax.swing.SwingUtilities

/** What one view's browser is wired to: the page it serves, its request policy, its router, and where faults go. */
internal class JcefViewWiring(
    val server: PluginPageServer,
    val policy: PluginViewRequestPolicy,
    val queries: PluginViewQueryRouter,
    val work: PluginViewWork,
    val onFault: (String) -> Unit,
)

/**
 * One live view's JCEF browser (plan section 7.3): its own [CefClient] and an in-memory
 * [CefRequestContext] (no cookies, storage or cache shared with any other view or the app), a
 * windowed browser whose AWT component the board embeds, the [PluginViewRequestPolicy] enforced on
 * every navigation and resource, popups, downloads and the context menu refused, and a
 * [CefMessageRouter] whose queries reach the view's port through [PluginViewQueryRouter].
 */
internal class JcefPluginBrowser private constructor(
    private val client: CefClient,
    private val context: CefRequestContext,
    private val router: CefMessageRouter,
    val browser: CefBrowser,
) : PluginBrowserHandle {
    override val component: Component get() = browser.uiComponent

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

    companion object {
        fun open(app: CefApp, wiring: JcefViewWiring): JcefPluginBrowser {
            val client = app.createClient()
            val config = CefMessageRouter.CefMessageRouterConfig(PluginPageShim.QUERY_FUNCTION, PluginPageShim.CANCEL_FUNCTION)
            val router = CefMessageRouter.create(config)
            router.addHandler(JcefQueryHandler(wiring.queries), true)
            client.addMessageRouter(router)
            client.addRequestHandler(RequestHandler(wiring))
            client.addLifeSpanHandler(JcefNoPopups)
            client.addDownloadHandler(JcefNoDownloads)
            client.addContextMenuHandler(JcefNoContextMenu)
            client.addLoadHandler(LoadFaults(wiring))
            val context = CefRequestContext.createContext(null)
            val browser = client.createBrowser(wiring.server.url, false, false, context)
            return JcefPluginBrowser(client, context, router, browser)
        }
    }
}

/** Every request through the [PluginViewRequestPolicy]; a renderer that dies hands its element back to the card. */
private class RequestHandler(private val wiring: JcefViewWiring) : CefRequestHandlerAdapter() {
    private val resources = ResourceHandler(wiring)

    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler {
        disableDefaultHandling?.set(isDownload)
        return resources
    }

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
 * other resource: a request the policy does not admit is cancelled before it is sent. The view's own
 * page (and 404s on its origin) come from the [PluginPageServer].
 */
private class ResourceHandler(private val wiring: JcefViewWiring) : CefResourceRequestHandlerAdapter() {
    override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean =
        !wiring.policy.admits(PluginRequest(request?.url.orEmpty(), kindOf(request?.resourceType)))

    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler? =
        when (wiring.policy.resource(request?.url.orEmpty())) {
            PluginResourceDecision.PAGE, PluginResourceDecision.NOT_FOUND -> PluginPageResourceHandler(wiring.server, wiring.work)
            else -> null
        }

    private fun kindOf(type: CefRequest.ResourceType?): PluginRequestKind = when (type) {
        CefRequest.ResourceType.RT_MAIN_FRAME -> PluginRequestKind.MAIN_FRAME
        CefRequest.ResourceType.RT_SUB_FRAME -> PluginRequestKind.SUB_FRAME
        else -> PluginRequestKind.RESOURCE
    }
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
