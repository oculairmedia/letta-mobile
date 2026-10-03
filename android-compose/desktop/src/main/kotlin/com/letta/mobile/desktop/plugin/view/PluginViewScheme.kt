package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.PluginViewPageRef
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

/**
 * The URLs desktop serves plugin pages under (plan section 7.3, letta-mobile-s416w.14):
 * `letta-plugin://<pluginId>/<pageId>?v=<version>`. The scheme is registered as standard and
 * secure, so each plugin is its own origin (`letta-plugin://<pluginId>`) and a page's `'self'`
 * never reaches another plugin or the app.
 */
internal object PluginViewScheme {
    const val SCHEME: String = "letta-plugin"

    private const val VERSION_PARAM = "v"
    private val PAGE_ID = Regex("^[a-z0-9-]{1,32}$")

    /** The URL [page] loads from. */
    fun urlOf(page: PluginViewPageRef): String =
        "$SCHEME://${page.pluginId}/${page.pageId}?$VERSION_PARAM=" + URLEncoder.encode(page.version, UTF_8)

    /** Whether [url] is on this scheme at all, whatever it names. */
    fun isPluginUrl(url: String): Boolean = url.startsWith("$SCHEME:", ignoreCase = true)

    /** The plugin whose origin [url] is on, or null when it is not a `letta-plugin` URL with a host. */
    fun pluginOf(url: String): String? = parse(url)?.host?.lowercase()

    /** The page [url] names, or null when it is not a page URL of exactly this shape. */
    fun pageOf(url: String): PluginViewPageRef? {
        val uri = parse(url) ?: return null
        val pluginId = uri.host?.lowercase() ?: return null
        val pageId = uri.path?.removePrefix("/")?.takeIf(PAGE_ID::matches) ?: return null
        val version = versionOf(uri.rawQuery) ?: return null
        return PluginViewPageRef(pluginId, version, pageId)
    }

    private fun parse(url: String): URI? {
        if (!isPluginUrl(url)) return null
        return runCatching { URI(url) }.getOrNull()?.takeIf { it.isAbsolute && it.rawFragment == null }
    }

    private fun versionOf(rawQuery: String?): String? {
        val params = rawQuery?.split('&') ?: return null
        val values = params.filter { it.startsWith("$VERSION_PARAM=") }
        if (values.size != 1 || params.size != 1) return null
        return runCatching { URLDecoder.decode(values.single().substringAfter('='), UTF_8) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
