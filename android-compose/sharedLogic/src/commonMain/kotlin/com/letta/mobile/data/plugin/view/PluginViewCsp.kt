package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginOrigin
import com.letta.mobile.data.plugin.PluginPage

/**
 * The Content-Security-Policy of a plugin page (plan section 7.2): a default-deny base widened only
 * by the manifest page's own `csp` allowlists. Each domain is parsed again as a [PluginOrigin]
 * (scheme, host, port), so nothing a manifest says can add a keyword, a directive or a path; an
 * entry that is not an origin is dropped, never passed on.
 */
object PluginViewCsp {
    /** The header name a host sends [header] under (desktop JCEF, the wasm iframe's server). */
    const val HEADER_NAME: String = "Content-Security-Policy"

    /** The policy as a header value: `default-src 'none'; script-src 'self' 'unsafe-inline'; …`. */
    fun header(page: PluginPage): String = directives(page).joinToString("; ") { (name, sources) -> (listOf(name) + sources).joinToString(" ") }

    /** The policy as a `<meta http-equiv>` tag for hosts that cannot set headers (Android WebView). */
    fun meta(page: PluginPage): String = "<meta http-equiv=\"$HEADER_NAME\" content=\"${attribute(header(page))}\">"

    /** Every directive, in order, with its sources; an empty allowlist is `'none'`. */
    fun directives(page: PluginPage): List<Pair<String, List<String>>> {
        val resources = origins(page.csp.resourceDomains)
        return listOf(
            "default-src" to listOf(NONE),
            "script-src" to listOf(SELF, UNSAFE_INLINE),
            "style-src" to listOf(SELF, UNSAFE_INLINE),
            "img-src" to listOf(SELF, "data:", "blob:") + resources,
            "font-src" to listOf(SELF) + resources,
            "media-src" to listOf(SELF, "blob:") + resources,
            "connect-src" to orNone(origins(page.csp.connectDomains)),
            "frame-src" to orNone(origins(page.csp.frameDomains)),
            "object-src" to listOf(NONE),
            "form-action" to listOf(NONE),
            "base-uri" to listOf(NONE),
        )
    }

    /** [domains] that are origins, normalised and without repeats; anything else is dropped. */
    fun origins(domains: List<String>): List<String> = domains.mapNotNull(PluginOrigin::parse).map(PluginOrigin::toString).distinct()

    private fun orNone(sources: List<String>): List<String> = sources.ifEmpty { listOf(NONE) }

    private fun attribute(text: String): String = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private const val NONE = "'none'"
    private const val SELF = "'self'"
    private const val UNSAFE_INLINE = "'unsafe-inline'"
}

/**
 * What a page may use of the device: the first-use permissions its manifest page declares,
 * intersected with what the person consented to on this device. Nothing outside both is granted.
 */
object PluginViewPermissions {
    /** The Permissions-Policy feature of each page permission. */
    val FEATURES: Map<PluginCapability, String> = mapOf(
        PluginCapability.UI_CAMERA to "camera",
        PluginCapability.UI_MICROPHONE to "microphone",
        PluginCapability.UI_GEOLOCATION to "geolocation",
        PluginCapability.UI_CLIPBOARD_WRITE to "clipboard-write",
    )

    /** The permissions [page] declares that [consented] also holds, and that a page may ask for at all. */
    fun effective(page: PluginPage, consented: Set<PluginCapability>): Set<PluginCapability> =
        page.permissions.toSet().intersect(consented).intersect(PluginCapability.pagePermissions.toSet())

    /** A `Permissions-Policy` header value: every feature off except the [effective] ones, for the page itself. */
    fun policy(page: PluginPage, consented: Set<PluginCapability>): String {
        val granted = effective(page, consented)
        return FEATURES.entries.joinToString(", ") { (capability, feature) -> if (capability in granted) "$feature=(self)" else "$feature=()" }
    }
}
