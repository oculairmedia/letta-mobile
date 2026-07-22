package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import java.net.URI

internal sealed interface DesktopDeepLinkDestination {
    data object Conversations : DesktopDeepLinkDestination
    data object Settings : DesktopDeepLinkDestination
    data class Conversation(val id: String) : DesktopDeepLinkDestination
    data class Agent(val id: String) : DesktopDeepLinkDestination
}

internal fun parseDesktopDeepLink(uri: URI): DesktopDeepLinkDestination? {
    if (!uri.scheme.equals("meridian", ignoreCase = true)) return null
    val id = uri.path.trim('/').takeIf(String::isNotBlank)
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
    deepLinks: StateFlow<URI?>,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onConversationSelected: (String) -> Unit,
    onAgentSelected: (String) -> Unit,
) {
    val pendingDeepLink by deepLinks.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        handleDesktopDeepLink(
            destination = pendingDeepLink?.let(::parseDesktopDeepLink),
            onDestinationSelected = onDestinationSelected,
            onConversationSelected = onConversationSelected,
            onAgentSelected = onAgentSelected,
        )
    }
}

private fun handleDesktopDeepLink(
    destination: DesktopDeepLinkDestination?,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onConversationSelected: (String) -> Unit,
    onAgentSelected: (String) -> Unit,
) {
    when (destination) {
        DesktopDeepLinkDestination.Settings -> onDestinationSelected(DesktopDestination.Settings)
        DesktopDeepLinkDestination.Conversations -> onDestinationSelected(DesktopDestination.Conversations)
        is DesktopDeepLinkDestination.Conversation -> {
            onConversationSelected(destination.id)
            onDestinationSelected(DesktopDestination.Conversations)
        }
        is DesktopDeepLinkDestination.Agent -> onAgentSelected(destination.id)
        null -> Unit
    }
}
