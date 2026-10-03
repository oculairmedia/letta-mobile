package com.letta.mobile.desktop.plugin.view

import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRequestContext
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefMenuModel
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
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
            router.addHandler(QueryHandler(wiring.queries), true)
            client.addMessageRouter(router)
            client.addRequestHandler(RequestHandler(wiring))
            client.addLifeSpanHandler(NoPopups)
            client.addDownloadHandler(NoDownloads)
            client.addContextMenuHandler(NoContextMenu)
            client.addLoadHandler(LoadFaults(wiring))
            val context = CefRequestContext.createContext(null)
            val browser = client.createBrowser(wiring.server.url, false, false, context)
            return JcefPluginBrowser(client, context, router, browser)
        }
    }
}

/** The page's `__lettaCefQuery` calls: accepted into the port or refused, never answered with data. */
private class QueryHandler(private val queries: PluginViewQueryRouter) : CefMessageRouterHandlerAdapter() {
    override fun onQuery(
        browser: CefBrowser?,
        frame: CefFrame?,
        queryId: Long,
        request: String?,
        persistent: Boolean,
        callback: CefQueryCallback?,
    ): Boolean {
        val outcome = queries.route(frame?.isMain == true, frame?.url, request.orEmpty())
        if (outcome == PluginQueryOutcome.ACCEPTED) callback?.success("") else callback?.failure(QUERY_REFUSED, outcome.name)
        return true
    }

    companion object {
        const val QUERY_REFUSED = 403
    }
}

/** Navigation and resources through the [PluginViewRequestPolicy]; the page itself from the [PluginPageServer]. */
private class RequestHandler(private val wiring: JcefViewWiring) : CefRequestHandlerAdapter() {
    private val resources = ResourceHandler(wiring)

    override fun onBeforeBrowse(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        userGesture: Boolean,
        isRedirect: Boolean,
    ): Boolean =
        !wiring.policy.allowsNavigation(request?.url.orEmpty(), mainFrame = frame?.isMain != false)

    override fun onOpenURLFromTab(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, userGesture: Boolean): Boolean = true

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

private class ResourceHandler(private val wiring: JcefViewWiring) : CefResourceRequestHandlerAdapter() {
    override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean =
        wiring.policy.resource(request?.url.orEmpty()) == PluginResourceDecision.BLOCKED

    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler? =
        when (wiring.policy.resource(request?.url.orEmpty())) {
            PluginResourceDecision.PAGE, PluginResourceDecision.NOT_FOUND -> PluginPageResourceHandler(wiring.server, wiring.work)
            else -> null
        }
}

/** A page that fails to load, or loads with anything but 200, hands its element back to the card. */
private class LoadFaults(private val wiring: JcefViewWiring) : CefLoadHandlerAdapter() {
    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
        if (frame?.isMain != true || httpStatusCode == PluginPageResponse.STATUS_OK) return
        wiring.onFault(wiring.server.failure ?: "the page answered $httpStatusCode")
    }

    override fun onLoadError(
        browser: CefBrowser?,
        frame: CefFrame?,
        errorCode: CefLoadHandler.ErrorCode?,
        errorText: String?,
        failedUrl: String?,
    ) {
        if (frame?.isMain != true || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
        wiring.onFault(wiring.server.failure ?: "the page did not load (${errorText ?: errorCode?.name})")
    }
}

private object NoPopups : CefLifeSpanHandlerAdapter() {
    override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean = true
}

/** Handled and never continued: CEF cancels the download when the callback is released. */
private object NoDownloads : CefDownloadHandlerAdapter() {
    override fun onBeforeDownload(
        browser: CefBrowser?,
        downloadItem: CefDownloadItem?,
        suggestedName: String?,
        callback: CefBeforeDownloadCallback?,
    ): Boolean = true
}

private object NoContextMenu : CefContextMenuHandlerAdapter() {
    override fun onBeforeContextMenu(browser: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams?, model: CefMenuModel?) {
        model?.clear()
    }
}
