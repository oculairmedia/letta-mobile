package com.letta.mobile.data.shell

import com.letta.mobile.data.lens.LensDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** letta-mobile-ckmub.3.4: the canonical route form (the web's route model) and `meridian://` links. */
class ShellRouteTest {
    @Test
    fun everyDestinationRoundTripsThroughItsRoute() {
        for (destination in ShellDestinationSamples.all) {
            val route = ShellRoute.encode(destination)
            assertEquals(destination, ShellRoute.decode(route), "route '$route'")
        }
    }

    @Test
    fun routesAreReadableAndEscapeTheirValues() {
        assertEquals("home", ShellRoute.encode(ShellDestination.Home))
        assertEquals("skills?section=tools", ShellRoute.encode(ShellDestination.Skills(section = ShellSkillsSection.Tools)))
        assertEquals("skills", ShellRoute.encode(ShellDestination.Skills()))
        assertEquals("runs", ShellRoute.encode(ShellDestination.Runs))
        assertEquals(ShellLink.Open(ShellDestination.Projects), ShellDeepLink.parse("meridian://projects"))
        assertEquals("chat?agent=a%20b&conversation=c%26d", ShellRoute.encode(ShellDestination.Chat("a b", "c&d")))
        assertEquals(ShellDestination.Chat("a b", "c&d"), ShellRoute.decode("chat?agent=a%20b&conversation=c%26d"))
    }

    @Test
    fun malformedRoutesDecodeToNull() {
        assertNull(ShellRoute.decode("nowhere"))
        assertNull(ShellRoute.decode("skills?section=gadgets"))
        assertNull(ShellRoute.decode("settings?section=unknown"))
        assertNull(ShellRoute.decode("chat?agent=%ZZ"))
        assertNull(ShellRoute.decode("chat/extra"))
    }

    @Test
    fun legacyMeridianLinksKeepTheirMeaning() {
        assertEquals(ShellLink.Open(ShellDestination.Chat()), ShellDeepLink.parse("meridian://conversations"))
        assertEquals(ShellLink.Open(ShellDestination.Settings()), ShellDeepLink.parse("meridian://settings"))
        assertEquals(
            ShellLink.Open(ShellDestination.Chat(conversationId = "conversation-42")),
            ShellDeepLink.parse("meridian://conversation/conversation-42"),
        )
        assertEquals(ShellLink.Open(ShellDestination.Chat(agentId = "agent-7")), ShellDeepLink.parse("meridian://agent/agent-7"))
    }

    @Test
    fun everyDestinationHasAMeridianLink() {
        for (destination in ShellDestinationSamples.all) {
            val link = ShellDeepLink.linkTo(destination)
            assertEquals(ShellLink.Open(destination), ShellDeepLink.parse(link), "link '$link'")
        }
        assertEquals(
            ShellLink.Open(ShellDestination.Skills(section = ShellSkillsSection.Tools)),
            ShellDeepLink.parse("meridian://skills?section=tools"),
        )
    }

    @Test
    fun foreignOpaqueOrIncompleteLinksAreIgnored() {
        assertNull(ShellDeepLink.parse("https://settings"))
        assertNull(ShellDeepLink.parse("meridian://conversation"))
        assertNull(ShellDeepLink.parse("meridian://agent/"))
        assertNull(ShellDeepLink.parse("meridian://unknown/value"))
        assertNull(ShellDeepLink.parse("meridian:settings"))
        assertNull(ShellDeepLink.parse("meridian:conversation/conversation-42"))
        assertNull(ShellDeepLink.parse("meridian://pair?qr=not-an-envelope"))
    }

    @Test
    fun pairLinksInEitherSchemeAreRedacted() {
        val meridian = "meridian://pair?qr=letta-qr-v1.eyJ2IjoxfQ&dial=203.0.113.7%3A4501"
        val letta = "letta://pair?qr=letta-qr-v1.eyJ2IjoxfQ"
        assertEquals(ShellLink.Pair(meridian), ShellDeepLink.parse(meridian))
        assertEquals(ShellLink.Pair(letta), ShellDeepLink.parse(letta))
        assertFalse("letta-qr-v1" in ShellLink.Pair(meridian).toString())
    }

    @Test
    fun drawerSectionsMapToDestinationsAndBack() {
        for (section in LensDestination.entries) {
            assertEquals(section, section.toShellDestination("agent-1").lensSection)
        }
        assertEquals(ShellDestination.Skills("agent-1"), LensDestination.Skills.toShellDestination("agent-1"))
        assertNull(ShellDestination.Home.lensSection)
    }
}

/** One of every destination shape, with and without its optional ids. */
object ShellDestinationSamples {
    val all: List<ShellDestination> = listOf(
        ShellDestination.Home,
        ShellDestination.Chat(),
        ShellDestination.Chat(agentId = "agent-1"),
        ShellDestination.Chat(agentId = "agent-1", conversationId = "conv-1"),
        ShellDestination.Chat(conversationId = "conv-1"),
        ShellDestination.Canvas(canvasId = "board-1"),
        ShellDestination.Canvas(conversationId = "conv-1", agentId = "agent-1"),
        ShellDestination.Memory(),
        ShellDestination.Memory("agent-1"),
        ShellDestination.Schedules(),
        ShellDestination.Schedules("agent-1"),
        ShellDestination.Channels,
        ShellDestination.Skills(),
        ShellDestination.Skills("agent-1"),
        ShellDestination.Skills(section = ShellSkillsSection.Tools),
        ShellDestination.Settings(),
        ShellDestination.Settings(ShellSettingsSection.Pairing),
        ShellDestination.AgentEditor("agent-1"),
        ShellDestination.AgentEditor(),
        ShellDestination.Providers,
        ShellDestination.Overview,
        ShellDestination.Runs,
        ShellDestination.Templates,
        ShellDestination.Projects,
        ShellDestination.Plugins,
    )
}
