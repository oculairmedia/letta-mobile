package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.PluginPageCsp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The page CSP over its default-deny base, and page permissions gated by consent (letta-mobile-s416w.31). */
class PluginViewCspTest {
    private fun page(csp: PluginPageCsp = PluginPageCsp(), permissions: List<PluginCapability> = emptyList()) =
        PluginPage(html = "pages/widget.html", csp = csp, permissions = permissions)

    private fun directives(header: String): Map<String, List<String>> =
        header.split("; ").associate { directive -> directive.split(" ").let { it.first() to it.drop(1) } }

    @Test
    fun aPageWithoutAllowlistsGetsTheDefaultDenyBase() {
        assertEquals(
            "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; font-src 'self'; media-src 'self' blob:; connect-src 'none'; frame-src 'none'; " +
                "object-src 'none'; form-action 'none'; base-uri 'none'",
            PluginViewCsp.header(page()),
        )
    }

    @Test
    fun eachAllowlistWidensOnlyItsDirectives() {
        val csp = PluginPageCsp(
            connectDomains = listOf("https://api.example.test", "wss://live.example.test:8443"),
            resourceDomains = listOf("https://*.cdn.example.test"),
            frameDomains = listOf("https://embed.example.test"),
        )
        val directives = directives(PluginViewCsp.header(page(csp)))
        assertEquals(listOf("https://api.example.test", "wss://live.example.test:8443"), directives["connect-src"])
        assertEquals(listOf("'self'", "data:", "blob:", "https://*.cdn.example.test"), directives["img-src"])
        assertEquals(listOf("'self'", "https://*.cdn.example.test"), directives["font-src"])
        assertEquals(listOf("https://embed.example.test"), directives["frame-src"])
        assertEquals(listOf("'self'", "'unsafe-inline'"), directives["script-src"], "scripts never come from an allowlist")
        assertEquals(listOf("'none'"), directives["default-src"])
    }

    @Test
    fun nothingButOriginsFromTheManifestEverGetsIn() {
        val hostile = listOf(
            "https://a.test; script-src *", "*", "'unsafe-eval'", "https://b.test/path", "data:", "https:", "javascript:alert(1)",
            "https://c.test 'unsafe-eval'", "ftp://d.test", "https://*.test",
        )
        val csp = PluginPageCsp(connectDomains = hostile, resourceDomains = hostile, frameDomains = hostile)
        val header = PluginViewCsp.header(page(csp))
        val directives = directives(header)
        assertEquals(PluginViewCsp.directives(page()).map { it.first }, directives.keys.toList(), "no directive is added or dropped")
        assertEquals(listOf("'none'"), directives["connect-src"])
        assertEquals(listOf("'none'"), directives["frame-src"])
        assertEquals(listOf("'self'", "data:", "blob:"), directives["img-src"])
        listOf("unsafe-eval", "*", "a.test", "b.test", "javascript").forEach { assertTrue(it !in header.replace("'unsafe-inline'", ""), it) }
    }

    @Test
    fun theBuilderNeverWidensBeyondTheManifest() {
        val manifestOrigins = setOf("https://api.example.test", "https://img.example.test", "https://embed.example.test")
        val csp = PluginPageCsp(listOf("https://api.example.test"), listOf("https://img.example.test"), listOf("https://embed.example.test", "https://embed.example.test"))
        val base = directives(PluginViewCsp.header(page()))
        directives(PluginViewCsp.header(page(csp))).forEach { (name, sources) ->
            val added = sources - base.getValue(name).toSet()
            assertTrue(manifestOrigins.containsAll(added), "$name added $added")
        }
        assertEquals(listOf("https://embed.example.test"), directives(PluginViewCsp.header(page(csp)))["frame-src"], "repeats collapse")
    }

    @Test
    fun theMetaTagCarriesTheSamePolicyEscaped() {
        val meta = PluginViewCsp.meta(page())
        assertEquals("<meta http-equiv=\"Content-Security-Policy\" content=\"${PluginViewCsp.header(page())}\">", meta)
        assertTrue("\"" !in PluginViewCsp.header(page()))
    }

    @Test
    fun permissionsAreTheDeclaredOnesTheUserConsentedTo() {
        val declared = page(permissions = listOf(PluginCapability.UI_CAMERA, PluginCapability.UI_CLIPBOARD_WRITE))
        val consented = setOf(PluginCapability.UI_CAMERA, PluginCapability.UI_MICROPHONE, PluginCapability.CANVAS_PLACE)
        assertEquals(setOf(PluginCapability.UI_CAMERA), PluginViewPermissions.effective(declared, consented))
        assertEquals(
            "camera=(self), microphone=(), geolocation=(), clipboard-write=()",
            PluginViewPermissions.policy(declared, consented),
        )
        assertEquals(emptySet(), PluginViewPermissions.effective(declared, emptySet()))
        val installTime = page(permissions = listOf(PluginCapability.NET_CONNECT))
        assertEquals(emptySet(), PluginViewPermissions.effective(installTime, setOf(PluginCapability.NET_CONNECT)), "only page permissions")
    }
}
