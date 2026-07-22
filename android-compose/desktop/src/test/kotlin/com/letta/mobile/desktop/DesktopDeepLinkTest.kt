package com.letta.mobile.desktop

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDeepLinkTest {
    @Test
    fun parsesSupportedMeridianDestinations() {
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
    }
}
