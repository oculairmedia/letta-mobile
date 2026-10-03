package com.letta.mobile.desktop.plugin.view

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefMenuModel
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefMessageRouterHandler
import org.cef.handler.CefMessageRouterHandlerAdapter

/** One `__lettaCefQuery` call as JCEF hands it over: the frame it came from and the request text. */
internal class JcefQueryCall(val frame: CefFrame?, val request: String?, val callback: CefQueryCallback?)

/** The page's `__lettaCefQuery` calls: accepted into the view's port or refused, never answered with data. */
internal class JcefQueryHandler(private val queries: PluginViewQueryRouter) {
    fun handle(call: JcefQueryCall) {
        val frame = call.frame
        val outcome = queries.route(PluginPageQuery(frame?.isMain == true, frame?.url, call.request.orEmpty()))
        if (outcome == PluginQueryOutcome.ACCEPTED) call.callback?.success("") else call.callback?.failure(QUERY_REFUSED, outcome.name)
    }

    /**
     * The router handler JCEF registers. Its fixed six-argument `onQuery` callback is captured into one
     * [JcefQueryCall] by a dynamic proxy, so no hand-written function here takes six parameters.
     */
    fun asRouterHandler(): CefMessageRouterHandler = Proxy.newProxyInstance(
        CefMessageRouterHandler::class.java.classLoader,
        arrayOf(CefMessageRouterHandler::class.java),
    ) { _, method, args ->
        val call = args?.takeIf { method.name == "onQuery" && it.size == ON_QUERY_ARITY }?.let {
            JcefQueryCall(it[FRAME_INDEX] as? CefFrame, it[REQUEST_INDEX] as? String, it[CALLBACK_INDEX] as? CefQueryCallback)
        }
        if (call != null) {
            handle(call)
            true
        } else {
            try {
                method.invoke(JcefInertRouterHandler, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    } as CefMessageRouterHandler

    private companion object {
        const val QUERY_REFUSED = 403
        const val ON_QUERY_ARITY = 6
        const val FRAME_INDEX = 1
        const val REQUEST_INDEX = 3
        const val CALLBACK_INDEX = 5
    }
}

/** A live view never opens a popup or a new tab. */
internal object JcefNoPopups : CefLifeSpanHandlerAdapter() {
    override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean = true
}

/** A live view never saves a download: handled and never continued, so CEF cancels it when the callback is released. */
internal object JcefNoDownloads : CefDownloadHandlerAdapter() {
    override fun onBeforeDownload(
        browser: CefBrowser?,
        downloadItem: CefDownloadItem?,
        suggestedName: String?,
        callback: CefBeforeDownloadCallback?,
    ): Boolean = true
}

/** A live view shows no context menu (no back, reload, view source or inspect). */
internal object JcefNoContextMenu : CefContextMenuHandlerAdapter() {
    override fun onBeforeContextMenu(browser: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams?, model: CefMenuModel?) {
        model?.clear()
    }
}

/** The router handler's inert defaults (native refs, cancelled queries), which the query proxy falls back to. */
private object JcefInertRouterHandler : CefMessageRouterHandlerAdapter()
