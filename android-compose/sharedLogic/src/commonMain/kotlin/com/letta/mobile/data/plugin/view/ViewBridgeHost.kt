package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.PluginOrigin

/**
 * What a platform host (the canvas live-view layer on Android, desktop or web) does for a bridge:
 * it says what the page sees and carries out what the bridge has already validated and allowed.
 */
interface ViewBridgeHost {
    /** What `host.context` carries right now. */
    fun context(): ViewHostContext

    /** The page asks for a frame of [width] by [height] CSS pixels; the host may grow it while the person has not sized it. */
    fun onResize(width: Double, height: Double)

    /** The page asks to be shown as [mode], one its manifest page declares; the answer is the mode the host settled on. */
    suspend fun requestDisplayMode(mode: PluginDisplayMode): PluginDisplayMode

    /** Opens [link] outside the view; the bridge calls it only after the page's permission, consent and the link policy. */
    suspend fun openLink(link: ViewLink)

    /** A page's `view.log` line. */
    fun onLog(level: ViewLogLevel, message: String) = Unit
}

/** Asks the person (once per device, remembered by the host) for a first-use capability a page declares. */
fun interface PluginViewConsent {
    suspend fun allow(capability: PluginCapability): Boolean

    companion object {
        val DenyAll: PluginViewConsent = PluginViewConsent { false }
    }
}

/** An http(s) URL a page asked to open, with its origin parts lower-cased. */
data class ViewLink(val url: String, val scheme: String, val host: String, val port: Int?) {
    companion object {
        private val SHAPE = Regex("^(https?)://([A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(:([0-9]{1,5}))?([/?#][^\\s]*)?$")

        /** [url] as a link, or null when it is not an absolute http or https URL without userinfo or whitespace. */
        fun parse(url: String): ViewLink? {
            val match = SHAPE.matchEntire(url) ?: return null
            val port = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
            return ViewLink(url, match.groupValues[1].lowercase(), match.groupValues[2].lowercase(), port)
        }
    }
}

/** Whether a page may open a link: the bridge asks it after the page's `ui:openLink` permission and the person's consent. */
fun interface ViewLinkPolicy {
    suspend fun allows(link: ViewLink): Boolean

    companion object {
        val DenyAll: ViewLinkPolicy = ViewLinkPolicy { false }

        /** Links to [origins] only (wildcards cover subdomains, as in `net.connect`). */
        fun allowlist(origins: List<PluginOrigin>): ViewLinkPolicy = ViewLinkPolicy { link -> origins.any { it.covers(link) } }

        /** Every link the person confirms through [confirm]. */
        fun confirm(confirm: suspend (ViewLink) -> Boolean): ViewLinkPolicy = ViewLinkPolicy { link -> confirm(link) }

        /** Links to [origins] at once; any other link only when the person confirms it. */
        fun allowlistOrConfirm(origins: List<PluginOrigin>, confirm: suspend (ViewLink) -> Boolean): ViewLinkPolicy {
            val allowlist = allowlist(origins)
            return ViewLinkPolicy { link -> allowlist.allows(link) || confirm(link) }
        }
    }
}

/** Whether this origin covers [link]: same scheme and port, and the same host or, for `*.`, a subdomain of it. */
fun PluginOrigin.covers(link: ViewLink): Boolean = scheme == link.scheme && port == link.port && hostCovers(link.host)

private fun PluginOrigin.hostCovers(other: String): Boolean =
    if (isWildcard) other.endsWith(host.removePrefix("*")) else host == other
