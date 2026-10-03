package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.PluginViewCsp
import com.letta.mobile.data.plugin.view.PluginViewPageRef
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.PluginViewUnavailableException
import com.letta.mobile.data.plugin.PluginCapability
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `letta-plugin` scheme without a browser (letta-mobile-s416w.14): its URLs, the headers every
 * response carries, the shim in front of the page, and 404/503 for anything that is not the page.
 */
class PluginPageServingTest {
    private val ref = PluginViewTestFixtures.pageRef

    @Test
    fun aPageUrlRoundTripsWithItsVersion() {
        val url = PluginViewScheme.urlOf(ref)
        assertEquals("letta-plugin://letta.example/widget?v=1.2.0%2Bbuild.7", url)
        assertEquals(ref, PluginViewScheme.pageOf(url))
        assertEquals(ref, PluginViewScheme.pageOf(url.replace("letta.example", "LETTA.Example")))
        assertEquals("letta.example", PluginViewScheme.pluginOf("letta-plugin://letta.example/other.css"))
    }

    @Test
    fun onlyTheExactPageShapeNamesAPage() {
        listOf(
            "https://letta.example/widget?v=1",
            "letta-plugin://letta.example/widget",
            "letta-plugin://letta.example/widget?v=1&x=2",
            "letta-plugin://letta.example/widget?v=1#frag",
            "letta-plugin://letta.example/a/b?v=1",
            "letta-plugin://letta.example/Widget?v=1",
            "letta-plugin:///widget?v=1",
            "letta-plugin://letta.example/widget?v=",
        ).forEach { assertNull(PluginViewScheme.pageOf(it), it) }
    }

    @Test
    fun everyResponseCarriesTheCspAndPermissionsPolicy() = runBlocking {
        val consented = setOf(PluginCapability.UI_CAMERA, PluginCapability.UI_MICROPHONE)
        val server = PluginPageServer(PluginViewTestFixtures.spec(), FakePageTransport(), consented)
        val page = server.respond(server.url)
        val missing = server.respond("letta-plugin://letta.example/secret?v=1.2.0%2Bbuild.7")

        listOf(page, missing).forEach { response ->
            assertEquals(PluginViewCsp.header(PluginViewTestFixtures.page), response.headers.getValue("Content-Security-Policy"))
            assertEquals(
                "camera=(self), microphone=(), geolocation=(), clipboard-write=()",
                response.headers.getValue(PluginPageServer.PERMISSIONS_POLICY),
            )
            assertEquals("nosniff", response.headers.getValue("X-Content-Type-Options"))
            assertEquals("no-store", response.headers.getValue("Cache-Control"))
            assertEquals("no-referrer", response.headers.getValue("Referrer-Policy"))
        }
        val csp = page.headers.getValue("Content-Security-Policy")
        assertTrue(csp.startsWith("default-src 'none'; script-src 'self' 'unsafe-inline';"), csp)
        assertTrue("https://*.cdn.example.com" in csp && "javascript" !in csp, csp)
        assertEquals(PluginPageResponse.STATUS_OK, page.status)
        assertEquals(PluginPageServer.HTML, page.mimeType)
        assertEquals(PluginPageResponse.STATUS_NOT_FOUND, missing.status)
    }

    @Test
    fun thePageGetsTheShimBeforeItsOwnScripts() = runBlocking {
        val transport = FakePageTransport("<!doctype html><html><head><script>mine()</script></head></html>")
        val server = PluginPageServer(PluginViewTestFixtures.spec(), transport, consented = emptySet())
        val html = server.respond(server.url).body.decodeToString()

        val shim = html.indexOf("window.__lettaViewPost=")
        assertTrue(shim > html.indexOf("<head>") && shim < html.indexOf("mine()"), html)
        assertTrue(html.startsWith("<!doctype html>"), html)
        assertTrue("window.__lettaCefQuery({request:String(t)" in html, html)
        assertTrue("window.__lettaViewPage=\"widget\"" in html, html)
        assertEquals(listOf(ref), transport.reads)
    }

    @Test
    fun theShimGoesAfterTheHeadTheHtmlOrTheDoctypeAndNeverBeforeIt() {
        fun at(html: String) = PluginPageShim.inject(html, "p").indexOf("<script>")
        assertEquals("<HEAD lang=x>".length, at("<HEAD lang=x><title>t</title>"))
        assertEquals("<html>".length, at("<html><body></body></html>"))
        assertEquals("<!DOCTYPE html>".length, at("<!DOCTYPE html><p>x</p>"))
        assertEquals(0, at("<p>no structure</p>"))
        assertEquals("<html>".length, at("<html><header>not a head</header></html>"))
    }

    @Test
    fun aPageTheTransportCannotReadIsA503WithItsReason() = runBlocking {
        val offline = PluginPageServer(PluginViewTestFixtures.spec(), PluginViewTransport.Unavailable, consented = emptySet())
        val response = offline.respond(offline.url)
        assertEquals(PluginPageResponse.STATUS_UNAVAILABLE, response.status)
        assertFalse(response.ok)
        assertEquals("no host connection for $ref", offline.failure)

        val broken = serverFailingWith(IllegalStateException("disk"))
        assertEquals(PluginPageResponse.STATUS_UNAVAILABLE, broken.respond(broken.url).status)
        assertEquals("disk", broken.failure)

        val unread = serverFailingWith(PluginViewUnavailableException("gone"))
        assertEquals(PluginPageResponse.STATUS_NOT_FOUND, unread.respond("letta-plugin://letta.example/other?v=1").status)
        assertNull(unread.failure, "a request for another page never reaches the transport")
    }

    private fun serverFailingWith(failure: Throwable) =
        PluginPageServer(PluginViewTestFixtures.spec(), FakePageTransport(failure = failure), consented = emptySet())

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

    @Test
    fun aPageRefPrintsAsTheTransportCachesIt() {
        assertEquals("letta.example@1.2.0+build.7/widget", PluginViewPageRef("letta.example", "1.2.0+build.7", "widget").toString())
    }
}
