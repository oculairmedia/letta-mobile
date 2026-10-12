package com.letta.mobile.desktop

import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellLink
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopDeepLinkTest {
    @Test
    fun parsesSupportedMeridianDestinations() {
        assertEquals(ShellLink.Open(ShellDestination.Chat()), parseDesktopDeepLink(URI("meridian://conversations")))
        assertEquals(ShellLink.Open(ShellDestination.Settings()), parseDesktopDeepLink(URI("meridian://settings")))
        assertEquals(
            ShellLink.Open(ShellDestination.Chat(conversationId = "conversation-42")),
            parseDesktopDeepLink(URI("meridian://conversation/conversation-42")),
        )
        assertEquals(
            ShellLink.Open(ShellDestination.Chat(agentId = "agent-7")),
            parseDesktopDeepLink(URI("meridian://agent/agent-7")),
        )
        // letta-mobile-ckmub.3.4: every shared destination has a link, e.g. the Tools anchor.
        assertEquals(
            ShellLink.Open(ShellDestination.Skills(section = ShellSkillsSection.Tools)),
            parseDesktopDeepLink(URI("meridian://skills?section=tools")),
        )
    }

    @Test
    fun rejectsForeignOrIncompleteLinks() {
        assertNull(parseDesktopDeepLink(URI("https://settings")))
        assertNull(parseDesktopDeepLink(URI("meridian://conversation")))
        assertNull(parseDesktopDeepLink(URI("meridian://unknown/value")))
        // Opaque form: no host/path, must be ignored rather than crash.
        assertNull(parseDesktopDeepLink(URI("meridian:settings")))
        assertNull(parseDesktopDeepLink(URI("meridian:conversation/conversation-42")))
    }

    // letta-mobile-dpv7m.3: a scanned pairing link opens the desktop's pairing card.
    @Test
    fun pairLinksRouteToPairingAndNeverPrintTheSecret() {
        val link = "meridian://pair?qr=letta-qr-v1.eyJ2IjoxfQ&dial=203.0.113.7%3A4501"
        assertEquals(ShellLink.Pair(link), parseDesktopDeepLink(URI(link)))
        assertNull(parseDesktopDeepLink(URI("meridian://pair?qr=not-an-envelope")))
        val request = DesktopDeepLinkRequest(1, URI(link))
        assertFalse("letta-qr-v1" in request.toString())
        assertFalse("letta-qr-v1" in ShellLink.Pair(link).toString())
    }

    @Test
    fun handlersRouteEachLinkKind() {
        val calls = mutableListOf<String>()
        val handlers = DesktopDeepLinkHandlers(
            onDestinationSelected = { calls += "destination:$it" },
            onConversationSelected = { calls += "conversation:$it" },
            onAgentSelected = { calls += "agent:$it" },
            onPairLink = { calls += "pair" },
        )
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://conversation/c1")), handlers)
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://agent/a1")), handlers)
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://pair?qr=letta-qr-v1.eyJ2IjoxfQ")), handlers)
        handleDesktopDeepLink(parseDesktopDeepLink(URI("meridian://memory")), handlers)
        handleDesktopDeepLink(null, handlers)
        assertEquals(
            listOf(
                "conversation:c1",
                "destination:${ShellDestination.Chat(conversationId = "c1")}",
                "agent:a1",
                "pair",
                "destination:${ShellDestination.Settings(ShellSettingsSection.Pairing)}",
                "destination:${ShellDestination.Memory()}",
            ),
            calls,
        )
    }
}
