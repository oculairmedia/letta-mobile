package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.view.PluginViewCsp
import com.letta.mobile.data.plugin.view.PluginViewPermissions
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.PluginViewUnavailableException
import kotlinx.coroutines.CancellationException

/** What the `letta-plugin` scheme answers one request with. */
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
        const val STATUS_NOT_FOUND = 404
        const val STATUS_UNAVAILABLE = 503
    }
}

/**
 * Serves one live view's page on the `letta-plugin` scheme: the page's bytes from the
 * [PluginViewTransport], the shim in front of its scripts, and the headers that sandbox it.
 * Anything else on the plugin's origin is a 404; a page the transport cannot read is a 503 and
 * [failure] says why, so the view can hand its element back to the fallback card.
 */
internal class PluginPageServer(
    private val spec: PluginViewSpec,
    private val transport: PluginViewTransport,
    consented: Set<PluginCapability>,
) {
    /** The URL the browser loads. */
    val url: String = PluginViewScheme.urlOf(spec.pageRef)

    /** Every response's headers: the page's CSP and Permissions-Policy, and no sniffing, caching or referrer. */
    val headers: Map<String, String> = mapOf(
        PluginViewCsp.HEADER_NAME to PluginViewCsp.header(spec.page),
        PERMISSIONS_POLICY to PluginViewPermissions.policy(spec.page, consented),
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to "no-store",
        "Referrer-Policy" to "no-referrer",
        "Cross-Origin-Opener-Policy" to "same-origin",
    )

    /** Why the last page request failed, or null when it has not. */
    @Volatile
    var failure: String? = null
        private set

    suspend fun respond(requestUrl: String): PluginPageResponse {
        if (PluginViewScheme.pageOf(requestUrl) != spec.pageRef) return error(PluginPageResponse.STATUS_NOT_FOUND, "Not Found")
        val html = runCatching { transport.readPage(spec.pageRef) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { return unavailable(reasonOf(it)) }
        val body = PluginPageShim.inject(html.decodeToString(), spec.pageId).encodeToByteArray()
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
        PluginPageResponse(status, text, "text/plain", headers, text.encodeToByteArray())

    companion object {
        const val PERMISSIONS_POLICY = "Permissions-Policy"
        const val HTML = "text/html"
    }
}
