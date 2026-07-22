package com.letta.mobile.desktop

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
