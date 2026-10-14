package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.view.PluginViewCsp
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.PluginViewUnavailableException
import com.letta.mobile.pluginview.PluginPagePolicy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How desktop serves a plugin page without a browser (letta-mobile-s416w.14, letta-mobile-cdgpr.3):
 * at the shared policy's `.invalid` URL, under the shared policy's headers, composed by the shared
 * page document with the desktop channel, and with the JCEF request gate reading the shared policy.
 */
class PluginPageServingTest {
    private val ref = PluginViewTestFixtures.pageRef
    private val page = PluginViewTestFixtures.pageUrl

    @Test
    fun thePageIsServedAtTheSharedPolicysUrlUnderItsHeaders() = runBlocking {
        val granted = setOf(PluginCapability.UI_CAMERA, PluginCapability.UI_MICROPHONE)
        val session = PluginViewTestFixtures.session(granted = granted)
        val server = PluginPageServer(session)
        assertEquals("https://plugin-view.letta.invalid/letta.example/1.2.0%2Bbuild.7/widget", server.url)

        val response = server.respond(server.url)
        assertEquals(PluginPageResponse.STATUS_OK, response.status)
        assertEquals(PluginPageServer.HTML, response.mimeType)
        assertEquals(PluginPagePolicy(session.spec).headers(session.permissions), response.headers)
        val csp = response.headers.getValue(PluginViewCsp.HEADER_NAME)
        assertEquals(PluginViewCsp.header(PluginViewTestFixtures.page) + "; " + PluginPagePolicy.SANDBOX, csp)
        assertTrue("https://*.cdn.example.com" in csp && "javascript" !in csp, csp)
        assertEquals(
            "camera=(self), microphone=(), geolocation=(), clipboard-write=()",
            response.headers.getValue(PluginPagePolicy.PERMISSIONS_POLICY),
        )
    }

    @Test
    fun anythingButThePageIsForbiddenWithoutReadingIt() = runBlocking {
        val transport = FakePageTransport()
        val server = PluginPageServer(PluginViewTestFixtures.session(transport = transport))
        listOf("$page/other", "https://plugin-view.letta.invalid/letta.other/1.0.0/widget", "https://api.example.com/").forEach {
            assertEquals(PluginPageResponse.STATUS_FORBIDDEN, server.respond(it).status, it)
        }
        assertEquals(emptyList(), transport.reads.toList())
    }

    @Test
    fun thePageGetsTheCspMetaTheChannelAndTheShimBeforeItsOwnScripts() = runBlocking {
        val transport = FakePageTransport("<!doctype html><html><head><script>mine()</script></head></html>")
        val server = PluginPageServer(PluginViewTestFixtures.session(transport = transport))
        val html = server.respond(server.url).body.decodeToString()

        val channel = html.indexOf("window.__lettaViewPost=")
        assertTrue(channel > html.indexOf("<head>") && channel < html.indexOf("mine()"), html)
        assertTrue(html.indexOf("<meta http-equiv=\"Content-Security-Policy\"") in html.indexOf("<head>")..channel, html)
        assertTrue(html.startsWith("<!doctype html>"), html)
        assertTrue("window.__lettaCefQuery({request:String(t)" in html, html)
        assertTrue("window.__lettaViewPage=\"widget\"" in html, html)
        assertEquals(listOf(ref), transport.reads.toList())
    }

    @Test
    fun aPageTheTransportCannotReadIsA503WithItsReason() = runBlocking {
        val offline = PluginPageServer(PluginViewTestFixtures.session(transport = PluginViewTransport.Unavailable))
        val response = offline.respond(offline.url)
        assertEquals(PluginPageResponse.STATUS_UNAVAILABLE, response.status)
        assertFalse(response.ok)
        assertEquals("no host connection for $ref", offline.failure)

        val broken = serverFailingWith(IllegalStateException("disk"))
        assertEquals(PluginPageResponse.STATUS_UNAVAILABLE, broken.respond(broken.url).status)
        assertEquals("disk", broken.failure)

        val unread = serverFailingWith(PluginViewUnavailableException("gone"))
        assertEquals(PluginPageResponse.STATUS_FORBIDDEN, unread.respond("$page/other").status)
        assertNull(unread.failure, "a request for another page never reaches the transport")
    }

    private fun serverFailingWith(failure: Throwable) =
        PluginPageServer(PluginViewTestFixtures.session(transport = FakePageTransport(failure = failure)))

    @Test
    fun theMainFrameOnlyEverShowsThePage() {
        val gate = JcefRequestGate(PluginPagePolicy(PluginViewTestFixtures.spec()))
        assertFalse(gate.cancels(page, mainFrame = true))
        assertTrue(gate.serves(page))
        listOf(
            "$page/other",
            "https://embed.example.com/",
            "https://api.example.com/",
            "about:blank",
            "data:text/html,hi",
            "file:///C:/Windows/win.ini",
            "chrome://settings",
        ).forEach { assertTrue(gate.cancels(it, mainFrame = true), it) }
    }

    @Test
    fun otherRequestsGoByTheSharedPolicyAndInlineUrlsByTheCsp() {
        val gate = JcefRequestGate(PluginPagePolicy(PluginViewTestFixtures.spec()))
        listOf(
            "https://img.cdn.example.com/a.png",
            "https://api.example.com/v1/jobs",
            "https://embed.example.com/player?id=1",
            "data:image/png;base64,AAAA",
            "BLOB:https://plugin-view.letta.invalid/1234",
        ).forEach { assertFalse(gate.cancels(it, mainFrame = false), it) }
        listOf(
            "$page/app.js",
            "https://plugin-view.letta.invalid/letta.other/1.0.0/widget",
            "https://cdn.example.com/a.png",
            "https://evil.example.org/",
            "wss://api.example.com/socket",
            "https://user:pw@api.example.com/",
            "file:///etc/passwd",
            "chrome-extension://abc/x.js",
            "javascript:alert(1)",
        ).forEach { assertTrue(gate.cancels(it, mainFrame = false), it) }
        assertFalse(gate.serves("https://api.example.com/v1/jobs"))
    }

    @Test
    fun aBodyIsReadOutInTheChunksCefAsksFor() {
        val body = PluginPageBody(ByteArray(10) { it.toByte() })
        val out = ByteArray(4)
        assertEquals(4, body.readInto(out, 4))
        assertEquals(listOf<Byte>(0, 1, 2, 3), out.toList())
        assertEquals(4, body.readInto(out, 8))
        assertEquals(2, body.readInto(out, 4))
        assertEquals(listOf<Byte>(8, 9), out.take(2))
        assertEquals(0, body.readInto(out, 4))
    }
}
