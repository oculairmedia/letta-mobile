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
    private const val MDNS_SUFFIX = ".local"

    /** A private or link-local IPv4 block: its first octet and the second octets it spans. */
    private class Block(val first: Int, val second: IntRange)

    private val PrivateBlocks = listOf(
        Block(10, 0..MAX_OCTET),
        Block(172, 16..31),
        Block(192, 168..168),
        Block(169, 254..254),
    )

    /** True when [url] is on this machine or a private network. */
    fun isLocal(url: SpeechEndpointUrl): Boolean {
        val host = SpeechEndpointAddress.resolve(url, emptyList())?.host ?: return false
        return LoopbackHost.matches(host) || isPrivateIpv4(host) || host.lowercase().endsWith(MDNS_SUFFIX)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').map(::octet)
        if (octets.size != OCTETS || null in octets) return false
        val (first, second) = octets.requireNoNulls()
        return PrivateBlocks.any { it.first == first && second in it.second }
    }

    /** One decimal octet without leading zeros, or null. */
    private fun octet(part: String): Int? = part.toIntOrNull()?.takeIf { it in 0..MAX_OCTET && part == it.toString() }
}
