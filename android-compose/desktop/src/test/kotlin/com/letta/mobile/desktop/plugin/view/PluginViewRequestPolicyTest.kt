package com.letta.mobile.desktop.plugin.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The request policy a live view's browser enforces behind the CSP (letta-mobile-s416w.14). */
class PluginViewRequestPolicyTest {
    private val policy = PluginViewRequestPolicy(PluginViewTestFixtures.pageRef, PluginViewTestFixtures.page.csp)
    private val page = PluginViewTestFixtures.pageUrl

    private fun main(url: String) = policy.admits(PluginRequest(url, PluginRequestKind.MAIN_FRAME))

    private fun sub(url: String) = policy.admits(PluginRequest(url, PluginRequestKind.SUB_FRAME))

    @Test
    fun aResourceIsAdmittedUnlessThePolicyBlocksIt() {
        assertTrue(policy.admits(PluginRequest("https://img.cdn.example.com/a.png", PluginRequestKind.RESOURCE)))
        assertTrue(policy.admits(PluginRequest("letta-plugin://letta.example/app.js", PluginRequestKind.RESOURCE)), "answered 404, not cancelled")
        assertFalse(policy.admits(PluginRequest("https://evil.example.org/", PluginRequestKind.RESOURCE)))
    }

    @Test
    fun theMainFrameOnlyEverShowsTheViewsOwnPage() {
        assertTrue(main(page))
        listOf(
            "letta-plugin://letta.example/other?v=1.2.0%2Bbuild.7",
            "letta-plugin://other.plugin/widget?v=1.2.0%2Bbuild.7",
            "https://embed.example.com/",
            "about:blank",
            "file:///C:/Windows/win.ini",
            "chrome://settings",
        ).forEach { assertFalse(main(it), it) }
    }

    @Test
    fun aSubframeShowsOnlyAFramedOriginOrABlankPage() {
        assertTrue(sub("https://embed.example.com/player?id=1"))
        assertTrue(sub("about:blank"))
        assertFalse(sub("http://embed.example.com/"), "the scheme is part of the origin")
        assertFalse(sub("https://embed.example.com:8443/"), "so is the port")
        assertFalse(sub("https://api.example.com/"), "a connect origin is not a frame origin")
        assertFalse(sub(page))
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
