package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.LcpViewShim

/**
 * How desktop puts the `lcp-view/1` shim in front of a page: an inline script, first in the
 * document's head, that defines `window.__lettaViewPost` over the JCEF message router and then
 * runs [LcpViewShim]. Being part of the served document, it runs before any of the page's own
 * scripts; the page's CSP allows inline scripts, so nothing has to be widened for it.
 */
internal object PluginPageShim {
    /** The message router's query function in the page; not `cefQuery`, so a page cannot mistake it for a general channel. */
    const val QUERY_FUNCTION: String = "__lettaCefQuery"
    const val CANCEL_FUNCTION: String = "__lettaCefQueryCancel"

    private val HEAD = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val HTML = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)

    /** The native channel, then the shim for page [pageId]. */
    fun bootstrap(pageId: String): String =
        "window.__lettaViewPost=function(t){window.$QUERY_FUNCTION({request:String(t),persistent:false," +
            "onSuccess:function(){},onFailure:function(){}})};" + LcpViewShim.inject(pageId)

    /**
     * [html] with the bootstrap script inserted right after its `<head>` tag, else after `<html>`,
     * else after the doctype (never before it, which would drop the page into quirks mode), else first.
     */
    fun inject(html: String, pageId: String): String {
        val script = "<script>${bootstrap(pageId)}</script>"
        val anchor = HEAD.find(html) ?: HTML.find(html) ?: DOCTYPE.find(html)
        val at = anchor?.range?.last?.plus(1) ?: 0
        return html.substring(0, at) + script + html.substring(at)
    }
}
