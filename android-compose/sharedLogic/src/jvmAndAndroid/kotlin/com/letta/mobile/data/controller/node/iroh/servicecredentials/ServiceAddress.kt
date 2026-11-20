package com.letta.mobile.data.controller.node.iroh.servicecredentials

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
     * A loopback IP literal, parsed strictly and never resolved: a dotted-quad IPv4 address in
     * `127.0.0.0/8`, or exactly `::1`. A name (`localhost`), a short or numeric spelling the system
     * resolver would expand (`2130706433`, `127.1`, `0x7f000001`) or look up (`9999999999`), and every
     * other IPv6 spelling are not literals, so none of them can resolve elsewhere than the secret's host.
     */
    private fun isLoopback(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        return bare == IPV6_LOOPBACK || ipv4Octets(bare)?.first() == IPV4_LOOPBACK_FIRST_OCTET
    }

    /** The four octets of a canonical dotted-quad IPv4 literal, or null for any other text. */
    private fun ipv4Octets(text: String): List<Int>? {
        val parts = text.split('.')
        if (parts.size != IPV4_OCTETS) return null
        val octets = parts.map { part ->
            part.takeIf { it.length in 1..MAX_OCTET_DIGITS && it.all { char -> char in '0'..'9' } && (it.length == 1 || it[0] != '0') }
                ?.toInt()?.takeIf { it <= MAX_OCTET }
        }
        return octets.takeIf { null !in it }?.filterNotNull()
    }

    private const val HTTP = "http"
    private const val HTTPS = "https"
    private const val IPV6_LOOPBACK = "::1"
    private const val IPV4_LOOPBACK_FIRST_OCTET = 127
    private const val IPV4_OCTETS = 4
    private const val MAX_OCTET_DIGITS = 3
    private const val MAX_OCTET = 255
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
}
