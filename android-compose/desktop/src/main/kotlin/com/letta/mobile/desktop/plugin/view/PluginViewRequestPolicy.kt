package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.PluginOrigin
import com.letta.mobile.data.plugin.PluginPageCsp
import com.letta.mobile.data.plugin.view.PluginViewPageRef
import com.letta.mobile.data.plugin.view.ViewLink
import com.letta.mobile.data.plugin.view.covers
import java.net.URI

/** What a live view's browser does with one resource request. */
internal enum class PluginResourceDecision {
    /** The view's own page: the [PluginPageServer] answers it. */
    PAGE,

    /** Elsewhere on the plugin's own origin: answered 404 (a package serves its page and nothing else). */
    NOT_FOUND,

    /** An origin the page's `csp` allowlists name: the network may have it (the CSP still applies). */
    NETWORK,

    /** `data:` and `blob:` URLs, which never leave the page. */
    INLINE,

    /** Anything else: cancelled before it is sent. */
    BLOCKED,
}

/**
 * The request policy of one live view (plan section 7.3): the second gate behind the CSP, enforced
 * in the browser's request handler. The main frame only ever shows the view's own page; a subframe
 * may show an origin the page's `frameDomains` names; a resource may come from the view's page,
 * `data:`/`blob:`, or an origin of `resourceDomains`, `connectDomains` or `frameDomains`. Nothing
 * of another plugin, the file system or the browser's own schemes is reachable.
 */
internal class PluginViewRequestPolicy(private val page: PluginViewPageRef, csp: PluginPageCsp) {
    private val frameOrigins = origins(csp.frameDomains)
    private val networkOrigins = origins(csp.resourceDomains + csp.connectDomains + csp.frameDomains)

    /** Whether a frame may navigate to [url]; the main frame only to the page, a subframe to a framed origin or `about:blank`. */
    fun allowsNavigation(url: String, mainFrame: Boolean): Boolean = when {
        mainFrame -> PluginViewScheme.pageOf(url) == page
        url == ABOUT_BLANK -> true
        else -> covered(url, frameOrigins)
    }

    fun resource(url: String): PluginResourceDecision = when {
        PluginViewScheme.isPluginUrl(url) -> ownOrigin(url)
        INLINE_SCHEMES.any { url.startsWith(it, ignoreCase = true) } -> PluginResourceDecision.INLINE
        covered(url, networkOrigins) -> PluginResourceDecision.NETWORK
        else -> PluginResourceDecision.BLOCKED
    }

    private fun ownOrigin(url: String): PluginResourceDecision = when {
        PluginViewScheme.pageOf(url) == page -> PluginResourceDecision.PAGE
        PluginViewScheme.pluginOf(url) == page.pluginId -> PluginResourceDecision.NOT_FOUND
        else -> PluginResourceDecision.BLOCKED
    }

    private fun covered(url: String, origins: List<PluginOrigin>): Boolean {
        val target = targetOf(url) ?: return false
        return origins.any { it.covers(target) }
    }

    companion object {
        private const val ABOUT_BLANK = "about:blank"
        private val INLINE_SCHEMES = listOf("data:", "blob:")
        private val NETWORK_SCHEMES = setOf("http", "https", "ws", "wss")

        private fun origins(domains: List<String>): List<PluginOrigin> = domains.mapNotNull(PluginOrigin::parse).distinct()

        /** [url]'s scheme, host and port, as an origin check reads them; null when it has no network origin. */
        internal fun targetOf(url: String): ViewLink? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it in NETWORK_SCHEMES } ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.rawUserInfo != null) return null
            return ViewLink(url, scheme, host, uri.port.takeIf { it >= 0 })
        }
    }
}
