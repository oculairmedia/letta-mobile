package com.letta.mobile.data.shell

import com.letta.mobile.data.pairing.PairLink

/** What an opened link asks the shell to do. */
sealed interface ShellLink {
    /** Show [destination]. */
    data class Open(val destination: ShellDestination) : ShellLink

    /**
     * A scan-to-pair link (`meridian://pair?…` / `letta://pair?…`). It carries
     * a one-time secret: hosts hand it over in memory and open their pairing surface; it is never
     * a route argument and never printed.
     */
    data class Pair(val link: String) : ShellLink {
        override fun toString(): String = "Pair(<redacted>)"
    }
}

/**
 * The one parser for `meridian://` links, shared by every client.
 *
 * - `meridian://pair?qr=…` (and the `letta://pair` alias) -> [ShellLink.Pair]
 * - `meridian://conversations` -> the chat page; `meridian://conversation/<id>` -> that conversation
 * - `meridian://agent/<id>` -> that agent's newest conversation; `meridian://settings` -> Settings
 * - `meridian://<canonical route>` (see [ShellRoute]), e.g. `meridian://skills?section=tools`
 *
 * Anything else, including the opaque `meridian:settings` form, is null.
 */
object ShellDeepLink {
    private const val SCHEME = "meridian://"

    fun parse(raw: String): ShellLink? {
        val value = raw.trim()
        if (PairLink.isPairLink(value)) return ShellLink.Pair(value)
        if (!value.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = value.substring(SCHEME.length)
        val head = rest.substringBefore('?').substringBefore('#')
        val host = head.substringBefore('/').lowercase()
        val id = head.substringAfter('/', missingDelimiterValue = "").trim('/').takeIf(String::isNotBlank)
        val destination = when (host) {
            "conversations" -> ShellDestination.Chat()
            "conversation" -> id?.let { ShellDestination.Chat(conversationId = it) }
            "agent" -> id?.let { ShellDestination.Chat(agentId = it) }
            else -> if (id == null) ShellRoute.decode(rest) else null
        }
        return destination?.let(ShellLink::Open)
    }

    /** The `meridian://` link that opens [destination]. */
    fun linkTo(destination: ShellDestination): String = SCHEME + ShellRoute.encode(destination)
}
