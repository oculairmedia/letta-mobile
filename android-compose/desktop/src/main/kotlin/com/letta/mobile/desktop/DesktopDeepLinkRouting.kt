package com.letta.mobile.desktop

import com.letta.mobile.data.pairing.PairLink
import java.net.URI

/**
 * One deep-link delivery. The [sequence] makes repeated activations of the
 * same URI distinct, so StateFlow's equality conflation cannot swallow them.
 */
internal data class DesktopDeepLinkRequest(val sequence: Long, val uri: URI) {
    /** A pairing link carries a one-time secret: never print the query. */
    override fun toString(): String = "DesktopDeepLinkRequest(sequence=$sequence, ${uri.scheme}://${uri.host})"
}

internal sealed interface DesktopDeepLinkDestination {
    data object Conversations : DesktopDeepLinkDestination
    data object Settings : DesktopDeepLinkDestination
    data class Conversation(val id: String) : DesktopDeepLinkDestination
    data class Agent(val id: String) : DesktopDeepLinkDestination

    /** letta-mobile-dpv7m.3: a scan-to-pair link (`meridian://pair?qr=…&dial=…`). */
    data class Pair(val link: String) : DesktopDeepLinkDestination {
        override fun toString(): String = "Pair(<redacted>)"
    }
}

internal fun parseDesktopDeepLink(uri: URI): DesktopDeepLinkDestination? {
    if (!uri.scheme.equals("meridian", ignoreCase = true)) return null
    if (PairLink.isPairLink(uri.toString())) return DesktopDeepLinkDestination.Pair(uri.toString())
    // Opaque activations like `meridian:settings` have no host or path;
    // treat them as unroutable instead of crashing the deep-link effect.
    val id = uri.path?.trim('/')?.takeIf(String::isNotBlank)
    return when (uri.host?.lowercase()) {
        "conversations" -> DesktopDeepLinkDestination.Conversations
        "settings" -> DesktopDeepLinkDestination.Settings
        "conversation" -> id?.let(DesktopDeepLinkDestination::Conversation)
        "agent" -> id?.let(DesktopDeepLinkDestination::Agent)
        else -> null
    }
}

/** Where each kind of deep link goes; grouped so the routing functions stay small. */
internal class DesktopDeepLinkHandlers(
    val onDestinationSelected: (DesktopDestination) -> Unit,
    val onConversationSelected: (String) -> Unit,
    val onAgentSelected: (String) -> Unit,
    /** letta-mobile-dpv7m.3: a scan-to-pair link; it carries a one-time secret. */
    val onPairLink: (String) -> Unit = {},
)

internal fun handleDesktopDeepLink(destination: DesktopDeepLinkDestination?, handlers: DesktopDeepLinkHandlers) {
    when (destination) {
        DesktopDeepLinkDestination.Settings -> handlers.onDestinationSelected(DesktopDestination.Settings)
        DesktopDeepLinkDestination.Conversations -> handlers.onDestinationSelected(DesktopDestination.Conversations)
        is DesktopDeepLinkDestination.Conversation -> {
            handlers.onConversationSelected(destination.id)
            handlers.onDestinationSelected(DesktopDestination.Conversations)
        }
        is DesktopDeepLinkDestination.Agent -> handlers.onAgentSelected(destination.id)
        is DesktopDeepLinkDestination.Pair -> {
            handlers.onPairLink(destination.link)
            handlers.onDestinationSelected(DesktopDestination.Settings)
        }
        null -> Unit
    }
}
