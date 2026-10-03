package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.PluginHostException
import com.letta.mobile.plugin.api.PluginHttpClient
import com.letta.mobile.plugin.api.PluginHttpResponse
import java.net.URI

/** The scheme, host and port of a URL: what `net.connect` allows or refuses. */
internal data class HttpOrigin(val scheme: String, val host: String, val port: Int?) {
    override fun toString(): String = "$scheme://$host" + (port?.let { ":$it" } ?: "")

    companion object {
        /** The origin of [url], or null when it has none. */
        fun of(url: String): HttpOrigin? = runCatching {
            val uri = URI(url)
            HttpOrigin(uri.scheme ?: return null, uri.host ?: return null, uri.port.takeIf { it != -1 })
        }.getOrNull()
    }
}

/** The manifest's `net.connect` origins: exact origins, or `*.`-wildcards covering the subdomains of a host. */
internal class OriginAllowlist(declared: List<String>) {
    /** One declared origin; a [wildcard] one covers the subdomains of its host. */
    private data class Entry(val origin: HttpOrigin, val wildcard: Boolean) {
        fun covers(other: HttpOrigin): Boolean = when {
            origin.scheme != other.scheme || origin.port != other.port -> false
            wildcard -> other.host.endsWith(".${origin.host}")
            else -> origin.host == other.host
        }
    }

    private val entries = declared.mapNotNull { text ->
        val wildcard = WILDCARD in text
        HttpOrigin.of(text.replace(WILDCARD, "://"))?.let { Entry(it, wildcard) }
    }

    fun admits(origin: HttpOrigin): Boolean = entries.any { it.covers(origin) }

    private companion object {
        const val WILDCARD = "://*."
    }
}

/**
 * The host's HTTP client as a jvm plugin sees it (plan section 3.3): it needs `net:connect`, and a
 * request goes out only to an origin the manifest's `net.connect` admits. Every request goes
 * through [admit] first (lifecycle and recording); a refused one is reported through [refuse].
 */
internal class AllowlistHttpClient(
    private val manifest: ConformanceManifest,
    private val admit: (PluginHttpRequest) -> Unit,
    private val refuse: (ConformanceFinding) -> Unit,
    private val handler: suspend (PluginHttpRequest) -> PluginHttpResponse,
) : PluginHttpClient {
    private val allowlist = OriginAllowlist(manifest.net.connect)

    override suspend fun get(url: String, headers: Map<String, String>): PluginHttpResponse =
        send(PluginHttpRequest(HttpMethod.GET, url, headers))

    override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>): PluginHttpResponse =
        send(PluginHttpRequest(HttpMethod.POST, url, headers, contentType))

    private suspend fun send(request: PluginHttpRequest): PluginHttpResponse {
        admit(request)
        refusal(request)?.let { refused ->
            refuse(ConformanceFinding(ConformanceRule.CAPABILITY, refused.message.orEmpty()))
            throw refused
        }
        return handler(request)
    }

    private fun refusal(request: PluginHttpRequest): PluginHostException? {
        val origin = HttpOrigin.of(request.url)
        return when {
            !manifest.has(ConformanceCapability.NET_CONNECT) ->
                PluginHostException(PluginHostException.CAPABILITY_DENIED, "the network needs the ${ConformanceCapability.NET_CONNECT} capability")
            origin == null || !allowlist.admits(origin) ->
                PluginHostException(PluginHostException.ORIGIN_DENIED, "${origin ?: request.url} is not one of the manifest's net.connect origins")
            else -> null
        }
    }
}
