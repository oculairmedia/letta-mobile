package com.letta.mobile.pluginview

import com.letta.mobile.data.plugin.view.LcpViewShim
import com.letta.mobile.data.plugin.view.PluginViewCsp
import com.letta.mobile.data.plugin.view.PluginViewSpec

/**
 * The document a host loads for a plugin page: the page's HTML with, at the top of its `<head>`,
 * the page's Content-Security-Policy as a `<meta>` (in force even where a host cannot send the
 * header) and one script that defines the platform's channel ([channelScript], which must define
 * `window.__lettaViewPost`) and then installs [LcpViewShim], so both run before any script of the page.
 */
object PluginPageDocument {
    private val HEAD = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val HTML = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)

    fun compose(html: String, spec: PluginViewSpec, channelScript: String): String {
        val injected = PluginViewCsp.meta(spec.page) + "<script>" + channelScript + LcpViewShim.inject(spec.pageId) + "</script>"
        HEAD.find(html)?.let { return html.insertAfter(it, injected) }
        val head = "<head>$injected</head>"
        val anchor = HTML.find(html) ?: DOCTYPE.find(html)
        return anchor?.let { html.insertAfter(it, head) } ?: (head + html)
    }

    private fun String.insertAfter(match: MatchResult, text: String): String {
        val end = match.range.last + 1
        return substring(0, end) + text + substring(end)
    }
}
