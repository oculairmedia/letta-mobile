package com.letta.mobile.desktop

import com.letta.mobile.data.shell.ShellDeepLink
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellLink
import com.letta.mobile.data.shell.ShellSettingsSection
import java.net.URI

/**
 * One deep-link delivery. The [sequence] makes repeated activations of the
 * same URI distinct, so StateFlow's equality conflation cannot swallow them.
 */
internal data class DesktopDeepLinkRequest(val sequence: Long, val uri: URI) {
    /** A pairing link carries a one-time secret: never print the query. */
    override fun toString(): String = "DesktopDeepLinkRequest(sequence=$sequence, ${uri.scheme}://${uri.host})"
}

/** letta-mobile-ckmub.3.4: `meridian://` links are parsed by the shared [ShellDeepLink]. */
internal fun parseDesktopDeepLink(uri: URI): ShellLink? = ShellDeepLink.parse(uri.toString())

/** Where each kind of deep link goes; grouped so the routing functions stay small. */
internal class DesktopDeepLinkHandlers(
    val onDestinationSelected: (ShellDestination) -> Unit,
    /** A conversation link; selected once the conversation list has it. */
    val onConversationSelected: (String) -> Unit,
    /** An agent link; opened once the first conversation load settled. */
    val onAgentSelected: (String) -> Unit,
    /** letta-mobile-dpv7m.3: a scan-to-pair link; it carries a one-time secret. */
    val onPairLink: (String) -> Unit = {},
)

internal fun handleDesktopDeepLink(link: ShellLink?, handlers: DesktopDeepLinkHandlers) {
    when (link) {
        is ShellLink.Pair -> {
            handlers.onPairLink(link.link)
            handlers.onDestinationSelected(ShellDestination.Settings(ShellSettingsSection.Pairing))
        }
        is ShellLink.Open -> openDeepLinkDestination(link.destination, handlers)
        null -> Unit
    }
}

private fun openDeepLinkDestination(destination: ShellDestination, handlers: DesktopDeepLinkHandlers) {
    val chat = destination as? ShellDestination.Chat
    val conversationId = chat?.conversationId
    val agentId = chat?.agentId
    when {
        conversationId != null -> {
            handlers.onConversationSelected(conversationId)
            handlers.onDestinationSelected(destination)
        }
        agentId != null -> handlers.onAgentSelected(agentId)
        else -> handlers.onDestinationSelected(destination)
    }
}
