package com.letta.mobile.data.controller.node.iroh.servicecredentials

import java.net.InetAddress
import java.net.URI

/**
 * The address rules of a service credential, on the host: a plain http(s) base URL (no user info,
 * query, fragment or whitespace), its origin, and whether a secret may travel to it (https, or plain
 * http to a loopback literal: `127.0.0.0/8` or `::1`).
 */
internal object ServiceAddress {
    /** The origin (`scheme://host:port`) of [baseUrl], or null when it is not a plain http(s) URL. */
    fun originOf(baseUrl: String): String? {
        val uri = parse(baseUrl) ?: return null
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == HTTPS) HTTPS_PORT else HTTP_PORT
        return "${uri.scheme}://${uri.host.lowercase()}:$port"
    }

    /** Whether a secret may be sent to [baseUrl]: https, or http to a loopback literal. */
    fun protectsSecret(baseUrl: String): Boolean {
        val uri = parse(baseUrl) ?: return false
        return uri.scheme == HTTPS || isLoopback(uri.host)
    }

    private fun parse(baseUrl: String): URI? {
        if (baseUrl.isEmpty() || baseUrl.any { it.isWhitespace() }) return null
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val plain = uri.scheme in setOf(HTTP, HTTPS) && !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        return uri.takeIf { plain }
    }

    /**
     * A loopback IP literal (`127.0.0.0/8`, `::1`). A name such as `localhost` never counts: a name can
     * resolve elsewhere, so only a literal proves the secret stays on this machine. Never resolves a name.
     */
    private fun isLoopback(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]").lowercase()
        val literal = bare.all { it.isDigit() || it == '.' } || ':' in bare
        return literal && runCatching { InetAddress.getByName(bare).isLoopbackAddress }.getOrDefault(false)
    }

    private const val HTTP = "http"
    private const val HTTPS = "https"
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
}
