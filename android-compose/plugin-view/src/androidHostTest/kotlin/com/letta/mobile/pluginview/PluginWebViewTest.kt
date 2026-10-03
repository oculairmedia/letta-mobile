package com.letta.mobile.pluginview

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.view.ViewAuditSink
import com.letta.mobile.data.plugin.view.PluginViewPageRef
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.PluginViewUnavailableException
import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewBridgeHost
import com.letta.mobile.data.plugin.view.ViewBridgeOptions
import com.letta.mobile.data.plugin.view.ViewBridgeServices
import com.letta.mobile.data.plugin.view.ViewBridgeState
import com.letta.mobile.data.plugin.view.ViewHostContext
import com.letta.mobile.data.plugin.view.ViewLink
import com.letta.mobile.ui.canvas.plugin.PluginPageChannel
import com.letta.mobile.ui.canvas.plugin.PluginViewSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboWebMessagePort
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android WebView host of a live plugin view (letta-mobile-s416w.13): the sandbox settings,
 * the page served with its policy and every other request refused unless allowlisted, the bridge
 * over the message channel from the page to the host and back, and the teardown that ends in a
 * destroyed WebView.
 */
@RunWith(RobolectricTestRunner::class)
class PluginWebViewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val failures = mutableListOf<String>()

    private object StaticHost : ViewBridgeHost {
        override fun context(): ViewHostContext = PluginViewFixtures.context

        override fun onResize(width: Double, height: Double) = Unit

        override suspend fun requestDisplayMode(mode: PluginDisplayMode): PluginDisplayMode = PluginDisplayMode.INLINE

        override suspend fun openLink(link: ViewLink) = Unit
    }

    private fun session(transport: PluginViewTransport = FakePluginViewTransport()): PluginViewSession {
        val channel = PluginPageChannel()
        val options = ViewBridgeOptions(audit = ViewAuditSink { }, teardownTimeoutMs = TEARDOWN_MS)
        val bridge = ViewBridge(PluginViewFixtures.spec, channel, ViewBridgeServices(StaticHost, transport), options)
        return PluginViewSession(bridge, channel, transport, granted = setOf(PluginCapability.UI_CAMERA))
    }

    /** The session of the view [open] made last. */
    private lateinit var opened: PluginViewSession

    private fun open(session: PluginViewSession = session()): PluginWebView {
        opened = session
        return PluginWebView(WebView(context), session, onFailure = { failures += it }, nonce = NONCE)
    }

    private fun bridgeState(): ViewBridgeState = opened.bridge.state.value

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun request(url: String, mainFrame: Boolean = false): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)

        override fun isForMainFrame(): Boolean = mainFrame

        override fun isRedirect(): Boolean = false

        override fun hasGesture(): Boolean = false

        override fun getMethod(): String = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    /** Starts [view], lets it load, and hands the page its end of the channel; returns the page's port. */
    private fun connect(view: PluginWebView): RoboWebMessagePort {
        view.start()
        idle()
        view.webView.webViewClient.onPageFinished(view.webView, view.pageUrl)
        idle()
        val (_, page) = shadowOf(view.webView).createdPorts.single()
        return page as RoboWebMessagePort
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theWebViewIsSandboxed() {
        val view = open()
        val settings = view.webView.settings
        val shadow = shadowOf(view.webView)

        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.domStorageEnabled)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        assertTrue(settings.safeBrowsingEnabled)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(shadow.geolocationEnabled, "geolocation is declared but not granted")
        assertTrue(shadow.webViewClient is PluginWebViewClient)
        assertTrue(shadow.webChromeClient is PluginWebChromeClient)
        assertNull(shadow.getJavascriptInterface("__lettaHost"), "no JavaScript interface: the bridge is a message channel")
    }

    @Test
    fun thePageIsServedWithItsPolicyAndTheShimOnceRead() {
        val transport = FakePluginViewTransport()
        val view = open(session(transport))
        val client = view.webView.webViewClient
        assertEquals(503, client.shouldInterceptRequest(view.webView, request(view.pageUrl, mainFrame = true))?.statusCode)

        view.start()
        idle()

        assertEquals(listOf(PluginViewFixtures.spec.pageRef), transport.pagesRead)
        assertEquals(view.pageUrl, shadowOf(view.webView).lastLoadedUrl)
        val page = assertNotNull(client.shouldInterceptRequest(view.webView, request(view.pageUrl, mainFrame = true)))
        assertEquals(200, page.statusCode)
        assertEquals("text/html", page.mimeType)
        val csp = page.responseHeaders.getValue("Content-Security-Policy")
        assertTrue(csp.startsWith("default-src 'none'") && csp.endsWith("sandbox allow-scripts"), csp)
        assertEquals("camera=(self), microphone=(), geolocation=(), clipboard-write=()", page.responseHeaders["Permissions-Policy"])
        val body = page.data.readBytes().decodeToString()
        assertTrue(body.startsWith("<!doctype html><html><head><meta http-equiv=\"Content-Security-Policy\""), body)
        assertTrue(PluginWebView.channelScript(NONCE) in body)
        assertTrue("window.lettaView" in body || "w.lettaView" in body)
        assertTrue(body.endsWith(PluginViewFixtures.WIDGET_HTML.substringAfter("<head>")))
    }

    @Test
    fun requestsOutsideTheAllowlistsAreRefusedAndAllowlistedOnesPass() {
        val view = open()
        val client = view.webView.webViewClient
        fun answer(url: String) = client.shouldInterceptRequest(view.webView, request(url))

        // fetch / XHR, an image, an iframe, a WebSocket's handshake URL and a file to an origin not on the lists.
        listOf(
            "https://evil.example/api",
            "https://evil.example/pixel.png",
            "https://evil.example/frame.html",
            "wss://evil.example/socket",
            "file:///data/data/com.letta.mobile/shared_prefs/x.xml",
        ).forEach { url ->
            val refused = assertNotNull(answer(url), url)
            assertEquals(403, refused.statusCode, url)
            assertEquals(0, refused.data.readBytes().size, url)
        }
        // The same kinds of request to the page's own allowlists go to the network.
        listOf(
            "https://api.example.test/v1/jobs",
            "https://cdn.example.test/pixel.png",
            "https://player.embed.example.test/frame.html",
            "ws://127.0.0.1:8188/ws",
        ).forEach { url -> assertNull(answer(url), url) }
        assertTrue(client.shouldOverrideUrlLoading(view.webView, request("https://api.example.test/", mainFrame = true)), "no navigation")
    }

    @Test
    fun aMessageRoundTripsFromThePageThroughTheBridgeAndBack() {
        val view = open()
        val page = connect(view)

        page.postMessage(WebMessage("""{"jsonrpc":"2.0","id":1,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}"""))
        idle()

        val reply = parse(page.receivedMessages.single())
        assertEquals("1", reply["id"]?.jsonPrimitive?.content)
        val result = assertNotNull(reply["result"]?.jsonObject)
        assertEquals("dark", result["theme"]?.jsonPrimitive?.content)
        assertEquals("el-1", result["element"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals(ViewBridgeState.READY, bridgeState())
        assertEquals(emptyList(), failures)
    }

    @Test
    fun theHandshakeGoesToTheMainFrameOnceAndOnlyForThePage() {
        val view = open()
        view.start()
        idle()
        view.webView.webViewClient.onPageFinished(view.webView, "https://evil.example/")
        idle()
        assertEquals(0, shadowOf(view.webView).createdPorts.size)

        view.webView.webViewClient.onPageFinished(view.webView, view.pageUrl)
        view.webView.webViewClient.onPageFinished(view.webView, view.pageUrl)
        idle()
        assertEquals(1, shadowOf(view.webView).createdPorts.size)
    }

    @Test
    fun closingAReadyViewTearsTheBridgeDownThenDestroysTheWebView() {
        val view = open()
        val page = connect(view)
        page.postMessage(WebMessage("""{"jsonrpc":"2.0","id":1,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}"""))
        idle()

        view.close(PluginWebView.REASON_REMOVED)
        idle()
        val teardown = parse(page.receivedMessages.last())
        assertEquals("host.teardown", teardown["method"]?.jsonPrimitive?.content)
        assertEquals("removed", teardown["params"]?.jsonObject?.get("reason")?.jsonPrimitive?.content)
        assertFalse(shadowOf(view.webView).wasDestroyCalled(), "the page gets its teardown before the WebView goes")

        page.postMessage(WebMessage("""{"jsonrpc":"2.0","id":${teardown["id"]},"result":{}}"""))
        idle()
        assertTrue(shadowOf(view.webView).wasDestroyCalled())
        assertEquals(ViewBridgeState.CLOSED, bridgeState())
        assertTrue(page.isClosed || page.connectedPort.isClosed)
    }

    @Test
    fun aPageThatNeverAnswersTheTeardownIsDestroyedAfterTheTimeout() {
        val view = open()
        val page = connect(view)
        page.postMessage(WebMessage("""{"jsonrpc":"2.0","id":1,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}"""))
        idle()

        view.close(PluginWebView.REASON_REMOVED)
        idle()
        assertFalse(shadowOf(view.webView).wasDestroyCalled())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TEARDOWN_MS + 50))
        assertTrue(shadowOf(view.webView).wasDestroyCalled())
    }

    @Test
    fun closingBeforeThePageLoadedDestroysAtOnceAndLoadsNothing() {
        val view = open()
        view.close(PluginWebView.REASON_REMOVED)
        idle()
        assertTrue(shadowOf(view.webView).wasDestroyCalled())
        assertEquals(ViewBridgeState.CLOSED, bridgeState())
    }

    @Test
    fun aFailedPageOrADeadRendererFallsBackToTheCard() {
        val view = open(session(FailingTransport()))
        view.start()
        idle()
        assertTrue(failures.single().startsWith("the page could not be read"), failures.toString())
        assertNull(shadowOf(view.webView).lastLoadedUrl)

        failures.clear()
        val other = open()
        val gone = object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = true

            override fun rendererPriorityAtExit(): Int = 0
        }
        assertTrue(other.webView.webViewClient.onRenderProcessGone(other.webView, gone))
        assertEquals(listOf("the page's renderer stopped"), failures)
    }

    @Test
    fun devicePermissionsAreGrantedOnlyAsFarAsThePageDeclaredAndThePersonGranted() {
        val chrome = PluginWebChromeClient(setOf(PluginCapability.UI_CAMERA))
        val asked = RecordingPermissionRequest(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        chrome.onPermissionRequest(asked)
        assertEquals(listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), asked.granted)

        val denied = RecordingPermissionRequest(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
        chrome.onPermissionRequest(denied)
        assertTrue(denied.denied)
    }

    private class FailingTransport : PluginViewTransport by FakePluginViewTransport() {
        override suspend fun readPage(page: PluginViewPageRef): ByteArray = throw PluginViewUnavailableException("offline")
    }

    private class RecordingPermissionRequest(private val asked: Array<String>) : PermissionRequest() {
        var granted: List<String>? = null
        var denied = false

        override fun getOrigin(): Uri = Uri.parse("https://plugin-view.letta.invalid/")

        override fun getResources(): Array<String> = asked

        override fun grant(resources: Array<String>) {
            granted = resources.toList()
        }

        override fun deny() {
            denied = true
        }
    }

    private companion object {
        const val NONCE = "00112233445566778899aabbccddeeff"
        const val TEARDOWN_MS = 300L
    }
}
