package com.letta.mobile.desktop

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopDeepLinkTest {
    @Test
    fun parsesSupportedMeridianDestinations() {
        assertEquals(
            DesktopDeepLinkDestination.Conversations,
            parseDesktopDeepLink(URI("meridian://conversations")),
        )
        assertEquals(
            DesktopDeepLinkDestination.Settings,
            parseDesktopDeepLink(URI("meridian://settings")),
        )
        assertEquals(
            DesktopDeepLinkDestination.Conversation("conversation-42"),
            parseDesktopDeepLink(URI("meridian://conversation/conversation-42")),
        )
        assertEquals(
            DesktopDeepLinkDestination.Agent("agent-7"),
            parseDesktopDeepLink(URI("meridian://agent/agent-7")),
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
        assertEquals(DesktopDeepLinkDestination.Pair(link), parseDesktopDeepLink(URI(link)))
        assertNull(parseDesktopDeepLink(URI("meridian://pair?qr=not-an-envelope")))
        val request = DesktopDeepLinkRequest(1, URI(link))
        assertFalse("letta-qr-v1" in request.toString())
        assertFalse("letta-qr-v1" in DesktopDeepLinkDestination.Pair(link).toString())
    }
}
