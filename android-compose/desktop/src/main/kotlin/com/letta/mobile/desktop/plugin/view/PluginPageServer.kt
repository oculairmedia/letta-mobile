package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.PluginViewUnavailableException
import com.letta.mobile.pluginview.PluginPageDocument
import com.letta.mobile.pluginview.PluginPagePolicy
import com.letta.mobile.pluginview.PluginPageRequest
import com.letta.mobile.ui.canvas.plugin.PluginViewSession
import kotlinx.coroutines.CancellationException

/** What the page's origin answers one request with. */
internal class PluginPageResponse(
    val status: Int,
    val statusText: String,
    val mimeType: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val ok: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = 200
        const val STATUS_FORBIDDEN = 403
        const val STATUS_UNAVAILABLE = 503
    }
}

/**
 * Serves one live view's page at the shared [PluginPagePolicy.pageUrl] (the `.invalid` host every
 * platform uses): the page's bytes from the session, composed by [PluginPageDocument] with the CSP
 * `<meta>`, the desktop channel and the shim in front of its scripts, under the policy's headers
 * (CSP with `sandbox allow-scripts`, so the document's origin is opaque, and Permissions-Policy).
 * Anything else is refused; a page that cannot be read is a 503 and [failure] says why, so the view
 * can hand its element back to the fallback card.
 */
internal class PluginPageServer(private val session: PluginViewSession) {
    val policy: PluginPagePolicy = PluginPagePolicy(session.spec)

    /** The URL the browser loads. */
    val url: String get() = policy.pageUrl

    /** The page's response headers, with the permissions the page may use here. */
    val headers: Map<String, String> = policy.headers(session.permissions)

    /** Why the last page request failed, or null when it has not. */
    @Volatile
    var failure: String? = null
        private set

    suspend fun respond(requestUrl: String): PluginPageResponse {
        // A fixed phrase: the refused URL is the page's to choose and never echoed back to it.
        if (policy.decide(requestUrl) != PluginPageRequest.Page) return error(PluginPageResponse.STATUS_FORBIDDEN, "Forbidden")
        val html = runCatching { session.readPage() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { return unavailable(reasonOf(it)) }
        val body = PluginPageDocument.compose(html.decodeToString(), session.spec, PluginPageShim.CHANNEL_SCRIPT).encodeToByteArray()
        return PluginPageResponse(PluginPageResponse.STATUS_OK, "OK", HTML, headers, body)
    }

    private fun unavailable(reason: String): PluginPageResponse {
        failure = reason
        return error(PluginPageResponse.STATUS_UNAVAILABLE, "Service Unavailable")
    }

    private fun reasonOf(thrown: Throwable): String = when (thrown) {
        is PluginViewUnavailableException -> thrown.message ?: "the page is unavailable"
        else -> thrown.message ?: thrown::class.simpleName.orEmpty()
    }

    private fun error(status: Int, text: String): PluginPageResponse =
        PluginPageResponse(status, text, "text/plain", mapOf("Cache-Control" to "no-store"), text.encodeToByteArray())

    companion object {
        const val HTML = "text/html"
    }
}

/**
 * The JCEF request handler's reading of the shared [PluginPagePolicy]: what Android's WebView does
 * with its client hooks, in one place. The main frame never navigates away from the page (Android
 * overrides every navigation); `data:` and `blob:` URLs never leave the engine and are governed by
 * the page's CSP alone (Android's WebView never shows them to its interceptor); every other request
 * goes by [PluginPagePolicy.decide], and a refused one is cancelled before it is sent.
 */
internal class JcefRequestGate(private val policy: PluginPagePolicy) {
    /** Whether a request for [url] (a main-frame navigation when [mainFrame]) is cancelled. */
    fun cancels(url: String, mainFrame: Boolean): Boolean = when {
        mainFrame -> url != policy.pageUrl
        INLINE_SCHEMES.any { url.startsWith(it, ignoreCase = true) } -> false
        else -> policy.decide(url) is PluginPageRequest.Refused
    }

    /** Whether [url] is the page itself, which the [PluginPageServer] answers. */
    fun serves(url: String): Boolean = policy.decide(url) == PluginPageRequest.Page

    private companion object {
        val INLINE_SCHEMES = listOf("data:", "blob:")
    }
}
