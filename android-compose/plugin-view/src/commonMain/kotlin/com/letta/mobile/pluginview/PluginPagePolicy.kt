package com.letta.mobile.pluginview

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginOrigin
import com.letta.mobile.data.plugin.view.PluginViewCsp
import com.letta.mobile.data.plugin.view.PluginViewPermissions
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.ViewLink
import com.letta.mobile.data.plugin.view.covers

/** What a host does with one request a plugin page makes. */
sealed interface PluginPageRequest {
    /** The page itself: serve the composed document with [PluginPagePolicy.headers]. */
    data object Page : PluginPageRequest

    /** An origin on the page's allowlists: let the engine load it. */
    data object Allowed : PluginPageRequest

    /** Anything else: answer it with a refusal, never with the network. */
    data class Refused(val reason: String) : PluginPageRequest
}

/**
 * The network policy of one plugin page (plan section 7.3), the same for every host:
 *
 *  - the page is served at [pageUrl], a synthetic `https` URL under the reserved `.invalid` TLD,
 *    so even a host that failed to intercept it could not reach a server for it;
 *  - any other request passes only when an origin on the page's CSP allowlists (connect, resource
 *    or frame domains) covers it, and is refused otherwise. Engines that do not show a host every
 *    request (Android does not show WebSockets) still hold to `connect-src` in the page's policy;
 *  - the page's response carries [headers]: the page's Content-Security-Policy with `sandbox
 *    allow-scripts`, so the document runs in an opaque origin with no cookies or storage, and its
 *    Permissions-Policy (everything off but what the page declares and the person granted).
 */
class PluginPagePolicy(private val spec: PluginViewSpec) {
    /** Where the page is loaded from. */
    val pageUrl: String = "https://$PAGE_HOST/" + listOf(spec.pluginId, spec.pluginVersion, spec.pageId).joinToString("/", transform = ::pathSegment)

    private val allowed: List<PluginOrigin> = with(spec.page.csp) { connectDomains + resourceDomains + frameDomains }
        .mapNotNull(PluginOrigin::parse)
        .map { it.copy(port = it.port.takeUnless { port -> port == defaultPort(it.scheme) }) }
        .distinct()

    /** What to do with a request for [url]. */
    fun decide(url: String): PluginPageRequest {
        if (url == pageUrl) return PluginPageRequest.Page
        val target = parse(url) ?: return PluginPageRequest.Refused("not a network URL")
        return when {
            target.host == PAGE_HOST -> PluginPageRequest.Refused("only the page itself is served from its origin")
            allowed.any { it.covers(target) } -> PluginPageRequest.Allowed
            else -> PluginPageRequest.Refused("${target.scheme}://${target.host} is not on the page's allowlists")
        }
    }

    /** The response headers of the page, with [granted] the first-use permissions the person granted the plugin. */
    fun headers(granted: Set<PluginCapability>): Map<String, String> = mapOf(
        PluginViewCsp.HEADER_NAME to PluginViewCsp.header(spec.page) + "; " + SANDBOX,
        PERMISSIONS_POLICY to PluginViewPermissions.policy(spec.page, granted),
        "Cache-Control" to "no-store",
        "Referrer-Policy" to "no-referrer",
        "X-Content-Type-Options" to "nosniff",
    )

    companion object {
        /** The host of every page URL; `.invalid` never resolves (RFC 2606). */
        const val PAGE_HOST: String = "plugin-view.letta.invalid"

        const val PERMISSIONS_POLICY: String = "Permissions-Policy"

        /** Scripts run; everything else a sandbox stops stays stopped, and the document's origin is opaque. */
        const val SANDBOX: String = "sandbox allow-scripts"

        private val URL = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(:([0-9]{1,5}))?([/?#].*)?$")
        private val SAFE_PATH = Regex("[A-Za-z0-9._~-]")
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443

        /** [url]'s scheme, host and port (null for the scheme's default), or null when it has userinfo or no host. */
        internal fun parse(url: String): ViewLink? {
            val match = URL.matchEntire(url) ?: return null
            val scheme = match.groupValues[1].lowercase()
            val port = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
            return ViewLink(url, scheme, match.groupValues[2].lowercase(), port.takeUnless { it == defaultPort(scheme) })
        }

        private fun defaultPort(scheme: String): Int? = when (scheme) {
            "http", "ws" -> HTTP_PORT
            "https", "wss" -> HTTPS_PORT
            else -> null
        }

        private fun pathSegment(text: String): String = text.map { char ->
            if (SAFE_PATH.matches(char.toString())) char.toString() else char.toString().encodeToByteArray().joinToString("") { "%" + it.toUByte().toString(16).padStart(2, '0') }
        }.joinToString("")
    }
}
