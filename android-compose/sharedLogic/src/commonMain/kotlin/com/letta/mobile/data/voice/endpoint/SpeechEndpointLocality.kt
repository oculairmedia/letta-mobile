package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.SpeechEndpointUrl

/**
 * Whether a speech endpoint runs on this machine or this network: a loopback host ([LoopbackHost]),
 * a private or link-local IPv4 literal (10/8, 172.16/12, 192.168/16, 169.254/16), or a `.local`
 * name. Anything else, and anything that is not a plain base URL, counts as remote. Used for
 * defaults that send more audio (live partial transcripts): on by default only where nothing
 * leaves the person's own network and no per-request cost applies.
 */
internal object SpeechEndpointLocality {
    private const val OCTETS = 4
    private const val MAX_OCTET = 255
    private const val PRIVATE_A = 10
    private const val PRIVATE_B = 172
    private const val PRIVATE_B_LOW = 16
    private const val PRIVATE_B_HIGH = 31
    private const val PRIVATE_C = 192
    private const val PRIVATE_C_SECOND = 168
    private const val LINK_LOCAL = 169
    private const val LINK_LOCAL_SECOND = 254
    private const val MDNS_SUFFIX = ".local"

    /** True when [url] is on this machine or a private network. */
    fun isLocal(url: SpeechEndpointUrl): Boolean {
        val host = SpeechEndpointAddress.resolve(url, emptyList())?.host ?: return false
        return LoopbackHost.matches(host) || isPrivateIpv4(host) || host.lowercase().endsWith(MDNS_SUFFIX)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').map { part -> part.toIntOrNull()?.takeIf { it in 0..MAX_OCTET && part == it.toString() } }
        if (octets.size != OCTETS || null in octets) return false
        val (first, second) = octets.requireNoNulls()
        return when (first) {
            PRIVATE_A -> true
            PRIVATE_B -> second in PRIVATE_B_LOW..PRIVATE_B_HIGH
            PRIVATE_C -> second == PRIVATE_C_SECOND
            LINK_LOCAL -> second == LINK_LOCAL_SECOND
            else -> false
        }
    }
}
