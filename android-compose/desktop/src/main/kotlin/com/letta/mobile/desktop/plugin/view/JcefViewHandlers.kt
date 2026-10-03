package com.letta.mobile.desktop.plugin.view

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
import org.cef.handler.CefMessageRouterHandlerAdapter

/** The page's `__lettaCefQuery` calls: accepted into the view's port or refused, never answered with data. */
internal class JcefQueryHandler(private val queries: PluginViewQueryRouter) : CefMessageRouterHandlerAdapter() {
    override fun onQuery(
        browser: CefBrowser?,
        frame: CefFrame?,
        queryId: Long,
        request: String?,
        persistent: Boolean,
        callback: CefQueryCallback?,
    ): Boolean {
        val outcome = queries.route(PluginPageQuery(frame?.isMain == true, frame?.url, request.orEmpty()))
        if (outcome == PluginQueryOutcome.ACCEPTED) callback?.success("") else callback?.failure(QUERY_REFUSED, outcome.name)
        return true
    }

    private companion object {
        const val QUERY_REFUSED = 403
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
