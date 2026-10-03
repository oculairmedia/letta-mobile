package com.letta.mobile.pluginview

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.ui.canvas.plugin.PluginViewSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import kotlin.concurrent.Volatile

/**
 * One plugin page in a sandboxed [WebView] (plan section 7.3, letta-mobile-s416w.13).
 *
 * The sandbox: JavaScript on (for the page and its shim) and nothing else a page could reach the
 * device with: no file or content access, no DOM storage, no third-party cookies, mixed content
 * refused, Safe Browsing on, no popups, no downloads, no navigation away. Every request goes
 * through [intercept]: the page itself is served from the transport's HTML with the shim, the CSP
 * `<meta>` and real CSP / Permissions-Policy headers ([PluginPagePolicy]); an origin on the page's
 * allowlists passes; anything else is refused without touching the network. The CSP's
 * `sandbox allow-scripts` gives the document an opaque origin, so it has no cookies or storage.
 *
 * The bridge's port is an HTML message channel ([WebView.createWebMessageChannel]): once the page
 * has loaded, one end is handed to the main frame with a per-view nonce, and the channel script
 * the document carries accepts only that handshake, so a frame inside the page cannot take the
 * channel over. What the page posts before then waits in the page.
 *
 * Lifecycle: [start] reads the page, loads it and runs the bridge; [close] tears the bridge down
 * (the page gets `host.teardown` and up to its timeout to answer), then destroys the WebView. Both
 * run in [scope], a main-thread scope owned by the screen (not the composition), so the teardown
 * outlives the composition that removed the element. A screen scope that is already gone still
 * gets the WebView destroyed.
 */
internal class PluginWebView(
    val webView: WebView,
    private val session: PluginViewSession,
    private val scope: CoroutineScope,
    private val onFailure: (reason: String) -> Unit,
    private val nonce: String = newNonce(),
) {
    private val policy = PluginPagePolicy(session.spec)
    private val port = CompletableDeferred<WebMessagePort>()
    private var running: Job? = null

    /** The composed page, set before the WebView is asked to load it; read on the WebView's IO thread. */
    @Volatile
    private var document: ByteArray? = null

    @Volatile
    private var closed = false

    /** The host's end of the channel once the page has its end. */
    @Volatile
    private var hostPort: WebMessagePort? = null

    val pageUrl: String get() = policy.pageUrl

    init {
        configure(session.permissions)
        webView.webViewClient = PluginWebViewClient(this)
        webView.webChromeClient = PluginWebChromeClient(session.permissions)
        webView.setDownloadListener { _, _, _, _, _ -> }
    }

    private fun configure(permissions: Set<PluginCapability>) {
        with(webView.settings) {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setGeolocationEnabled(PluginCapability.UI_GEOLOCATION in permissions)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    /** Reads the page, loads it, and runs the bridge and the delivery of its messages to the page. */
    fun start() {
        if (closed || running != null) return
        running = scope.launch {
            launch { session.bridge.run() }
            launch { deliverToPage() }
            load()
        }
    }

    private suspend fun load() {
        val html = runCatching { session.readPage() }.getOrElse { error ->
            if (error is CancellationException) throw error
            fail("the page could not be read: ${error.message}")
            return
        }
        document = PluginPageDocument.compose(html.decodeToString(), session.spec, channelScript(nonce)).encodeToByteArray()
        if (!closed) webView.loadUrl(policy.pageUrl)
    }

    /** The answer to a request the page makes, or null to let the WebView load it. */
    fun intercept(url: String): WebResourceResponse? = when (policy.decide(url)) {
        PluginPageRequest.Page -> document?.let(::pageResponse) ?: refusal(STATUS_UNAVAILABLE, "Service Unavailable")
        PluginPageRequest.Allowed -> null
        // A fixed phrase: the refused URL is the page's to choose and never echoed back to it.
        is PluginPageRequest.Refused -> refusal(STATUS_FORBIDDEN, "Forbidden")
    }

    private fun pageResponse(bytes: ByteArray): WebResourceResponse =
        WebResourceResponse(HTML, UTF_8, STATUS_OK, "OK", policy.headers(session.permissions), ByteArrayInputStream(bytes))

    private fun refusal(status: Int, phrase: String): WebResourceResponse =
        WebResourceResponse(TEXT, UTF_8, status, phrase, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    /** The page finished loading: hand it its end of the channel, once. */
    fun onPageFinished(url: String?) {
        if (!awaitsHandshake(url)) return
        val (host, page) = webView.createWebMessageChannel()
        host.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage?) {
                message?.data?.let(session.page::postFromPage)
            }
        })
        webView.postWebMessage(WebMessage(handshake(nonce), arrayOf(page)), Uri.parse("*"))
        hostPort = host
        port.complete(host)
    }

    /** Whether [url] is the page having loaded while it has no channel yet. */
    private fun awaitsHandshake(url: String?): Boolean = url == policy.pageUrl && !port.isCompleted && !closed

    private suspend fun deliverToPage() {
        val host = port.await()
        session.page.toPage.collect { json -> host.postMessage(WebMessage(json)) }
    }

    /** The page cannot be shown: the board falls back to the card, which ends this view. */
    fun fail(reason: String) {
        if (!closed) onFailure(reason)
    }

    /** Tears the bridge down, then destroys the WebView; once. */
    fun close(reason: String) {
        if (closed) return
        closed = true
        if (!scope.isActive) {
            release()
            return
        }
        scope.launch {
            try {
                withContext(NonCancellable) { session.bridge.teardown(reason) }
            } finally {
                release()
            }
        }
    }

    private fun release() {
        session.page.close()
        hostPort?.close()
        running?.cancel()
        webView.destroy()
    }

    companion object {
        const val REASON_REMOVED: String = "removed"

        private const val HTML = "text/html"
        private const val TEXT = "text/plain"
        private const val UTF_8 = "utf-8"
        private const val STATUS_OK = 200
        private const val STATUS_FORBIDDEN = 403
        private const val STATUS_UNAVAILABLE = 503
        private const val NONCE_BYTES = 16
        private const val HANDSHAKE = "lcp-view/1:"

        fun handshake(nonce: String): String = HANDSHAKE + nonce

        /**
         * The page's end of the channel: `window.__lettaViewPost` queues until the host's handshake
         * (carrying [nonce]) arrives with the port, then posts on it; what the host sends goes to
         * `window.__lettaViewReceive`. The handshake event stops here, so the page never sees it.
         */
        fun channelScript(nonce: String): String =
            "(function(w){var p=null,q=[],k=\"" + handshake(nonce) + "\";" +
                "w.__lettaViewPost=function(t){p?p.postMessage(t):q.push(t)};" +
                "w.addEventListener(\"message\",function(e){if(p||e.data!==k||!e.ports||!e.ports.length)return;" +
                "e.stopImmediatePropagation();p=e.ports[0];p.onmessage=function(m){w.__lettaViewReceive(m.data)};" +
                "q.splice(0).forEach(function(t){p.postMessage(t)})},true)})(window);"

        private fun newNonce(): String {
            val bytes = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}
