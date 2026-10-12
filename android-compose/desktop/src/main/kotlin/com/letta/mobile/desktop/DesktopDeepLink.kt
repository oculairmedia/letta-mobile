package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
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

@Composable
internal fun DesktopDeepLinkEffect(
    deepLinks: StateFlow<DesktopDeepLinkRequest?>,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onConversationSelected: (String) -> Unit,
    onAgentSelected: (String) -> Unit,
    onPairLink: (String) -> Unit = {},
) {
    val pendingDeepLink by deepLinks.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        handleDesktopDeepLink(
            destination = pendingDeepLink?.uri?.let(::parseDesktopDeepLink),
            onDestinationSelected = onDestinationSelected,
            onConversationSelected = onConversationSelected,
            onAgentSelected = onAgentSelected,
            onPairLink = onPairLink,
        )
    }
}

private fun handleDesktopDeepLink(
    destination: DesktopDeepLinkDestination?,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onConversationSelected: (String) -> Unit,
    onAgentSelected: (String) -> Unit,
    onPairLink: (String) -> Unit,
) {
    when (destination) {
        DesktopDeepLinkDestination.Settings -> onDestinationSelected(DesktopDestination.Settings)
        DesktopDeepLinkDestination.Conversations -> onDestinationSelected(DesktopDestination.Conversations)
        is DesktopDeepLinkDestination.Conversation -> {
            onConversationSelected(destination.id)
            onDestinationSelected(DesktopDestination.Conversations)
        }
        is DesktopDeepLinkDestination.Agent -> onAgentSelected(destination.id)
        is DesktopDeepLinkDestination.Pair -> {
            onPairLink(destination.link)
            onDestinationSelected(DesktopDestination.Settings)
        }
        null -> Unit
    }
}
