package com.letta.mobile.pluginview

import com.letta.mobile.data.plugin.PluginCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Where a plugin page's requests may go, and the headers its page is served with (letta-mobile-s416w.13). */
class PluginPagePolicyTest {
    private val policy = PluginPagePolicy(PluginViewFixtures.spec)

    @Test
    fun thePageIsServedFromAnUnresolvableHttpsUrl() {
        assertEquals("https://plugin-view.letta.invalid/letta.example/1.2.0/widget", policy.pageUrl)
        assertEquals(PluginPageRequest.Page, policy.decide(policy.pageUrl))
        assertIs<PluginPageRequest.Refused>(policy.decide("https://plugin-view.letta.invalid/letta.example/1.2.0/other"))
        assertIs<PluginPageRequest.Refused>(policy.decide("https://plugin-view.letta.invalid/letta.other/1.0.0/widget"))
    }

    @Test
    fun allowlistedOriginsPassForEveryKindOfRequest() {
        listOf(
            "https://api.example.test/v1/jobs?x=1",
            "https://api.example.test:443/v1/jobs",
            "https://cdn.example.test/img/a.png",
            "ws://127.0.0.1:8188/ws",
            "https://player.embed.example.test/frame",
            "HTTPS://API.EXAMPLE.TEST/upper",
        ).forEach { url -> assertEquals(PluginPageRequest.Allowed, policy.decide(url), url) }
    }

    @Test
    fun everythingElseIsRefused() {
        listOf(
            "https://evil.example/fetch",
            "https://api.example.test:8443/other-port",
            "http://api.example.test/wrong-scheme",
            "wss://evil.example/socket",
            "ws://127.0.0.1:9999/other-port",
            "https://embed.example.test/wildcard-needs-a-subdomain",
            "https://user:pass@api.example.test/userinfo",
            "file:///sdcard/secret.txt",
            "content://contacts/people",
            "javascript:alert(1)",
            "data:text/html,hi",
            "not a url",
        ).forEach { url -> assertIs<PluginPageRequest.Refused>(policy.decide(url), url) }
    }

    @Test
    fun thePageCarriesItsPolicyAsRealHeadersInAnOpaqueOrigin() {
        val headers = policy.headers(granted = setOf(PluginCapability.UI_CAMERA, PluginCapability.UI_MICROPHONE))
        val csp = headers.getValue("Content-Security-Policy")
        assertTrue(csp.startsWith("default-src 'none'; script-src 'self' 'unsafe-inline'"), csp)
        assertTrue("connect-src https://api.example.test ws://127.0.0.1:8188;" in csp, csp)
        assertTrue("frame-src https://*.embed.example.test;" in csp, csp)
        assertTrue(csp.endsWith("; sandbox allow-scripts"), csp)
        assertTrue("not an origin" !in csp, csp)
        // Camera is declared and granted; the microphone is granted but not declared; geolocation declared but not granted.
        assertEquals("camera=(self), microphone=(), geolocation=(), clipboard-write=()", headers.getValue("Permissions-Policy"))
        assertEquals("no-store", headers.getValue("Cache-Control"))
    }
}
