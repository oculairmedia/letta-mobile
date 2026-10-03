package com.letta.mobile.desktop.plugin.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The request policy a live view's browser enforces behind the CSP (letta-mobile-s416w.14). */
class PluginViewRequestPolicyTest {
    private val policy = PluginViewRequestPolicy(PluginViewTestFixtures.pageRef, PluginViewTestFixtures.page.csp)
    private val page = PluginViewTestFixtures.pageUrl

    @Test
    fun theMainFrameOnlyEverShowsTheViewsOwnPage() {
        assertTrue(policy.allowsNavigation(page, mainFrame = true))
        listOf(
            "letta-plugin://letta.example/other?v=1.2.0%2Bbuild.7",
            "letta-plugin://other.plugin/widget?v=1.2.0%2Bbuild.7",
            "https://embed.example.com/",
            "about:blank",
            "file:///C:/Windows/win.ini",
            "chrome://settings",
        ).forEach { assertFalse(policy.allowsNavigation(it, mainFrame = true), it) }
    }

    @Test
    fun aSubframeShowsOnlyAFramedOriginOrABlankPage() {
        assertTrue(policy.allowsNavigation("https://embed.example.com/player?id=1", mainFrame = false))
        assertTrue(policy.allowsNavigation("about:blank", mainFrame = false))
        assertFalse(policy.allowsNavigation("http://embed.example.com/", mainFrame = false), "the scheme is part of the origin")
        assertFalse(policy.allowsNavigation("https://embed.example.com:8443/", mainFrame = false), "so is the port")
        assertFalse(policy.allowsNavigation("https://api.example.com/", mainFrame = false), "a connect origin is not a frame origin")
        assertFalse(policy.allowsNavigation(page, mainFrame = false))
    }

    @Test
    fun resourcesComeFromThePageInlineUrlsOrAllowlistedOrigins() {
        assertEquals(PluginResourceDecision.PAGE, policy.resource(page))
        assertEquals(PluginResourceDecision.NOT_FOUND, policy.resource("letta-plugin://letta.example/app.js"))
        assertEquals(PluginResourceDecision.BLOCKED, policy.resource("letta-plugin://other.plugin/widget?v=1"))
        assertEquals(PluginResourceDecision.INLINE, policy.resource("data:image/png;base64,AAAA"))
        assertEquals(PluginResourceDecision.INLINE, policy.resource("blob:letta-plugin://letta.example/1234"))
        assertEquals(PluginResourceDecision.NETWORK, policy.resource("https://img.cdn.example.com/a.png"))
        assertEquals(PluginResourceDecision.NETWORK, policy.resource("https://api.example.com/v1/jobs"))
        assertEquals(PluginResourceDecision.NETWORK, policy.resource("https://embed.example.com/player"))
        listOf(
            "https://cdn.example.com/a.png",
            "https://evil.example.org/",
            "wss://api.example.com/socket",
            "https://user:pw@api.example.com/",
            "file:///etc/passwd",
            "chrome-extension://abc/x.js",
            "javascript:alert(1)",
        ).forEach { assertEquals(PluginResourceDecision.BLOCKED, policy.resource(it), it) }
    }
}
