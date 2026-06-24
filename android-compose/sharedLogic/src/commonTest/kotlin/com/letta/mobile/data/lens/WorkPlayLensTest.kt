package com.letta.mobile.data.lens

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkPlayLensTest {

    @Test
    fun testModeOther() {
        assertEquals(WorkPlayMode.Play, WorkPlayMode.Work.other)
        assertEquals(WorkPlayMode.Work, WorkPlayMode.Play.other)
    }

    @Test
    fun testModeLabel() {
        assertEquals("Work", WorkPlayLens.modeLabel(WorkPlayMode.Work))
        assertEquals("Play", WorkPlayLens.modeLabel(WorkPlayMode.Play))
    }

    @Test
    fun testDestinationLabel() {
        // Work mode
        assertEquals("Memory", WorkPlayLens.destinationLabel(WorkPlayMode.Work, LensDestination.Memory))
        assertEquals("Schedules", WorkPlayLens.destinationLabel(WorkPlayMode.Work, LensDestination.Schedules))
        assertEquals("Channels", WorkPlayLens.destinationLabel(WorkPlayMode.Work, LensDestination.Channels))
        assertEquals("Skills", WorkPlayLens.destinationLabel(WorkPlayMode.Work, LensDestination.Skills))
        assertEquals("Chats", WorkPlayLens.destinationLabel(WorkPlayMode.Work, LensDestination.Conversations))

        // Play mode
        assertEquals("Worlds & Lore", WorkPlayLens.destinationLabel(WorkPlayMode.Play, LensDestination.Memory))
        assertEquals("Schedules", WorkPlayLens.destinationLabel(WorkPlayMode.Play, LensDestination.Schedules))
        assertEquals("Channels", WorkPlayLens.destinationLabel(WorkPlayMode.Play, LensDestination.Channels))
        assertEquals("Characters", WorkPlayLens.destinationLabel(WorkPlayMode.Play, LensDestination.Skills))
        assertEquals("Scenes", WorkPlayLens.destinationLabel(WorkPlayMode.Play, LensDestination.Conversations))
    }

    @Test
    fun testNavDestinations() {
        val workDestinations = WorkPlayLens.navDestinations(WorkPlayMode.Work)
        assertEquals(listOf(LensDestination.Memory, LensDestination.Schedules, LensDestination.Channels, LensDestination.Skills), workDestinations)

        val playDestinations = WorkPlayLens.navDestinations(WorkPlayMode.Play)
        assertEquals(listOf(LensDestination.Skills, LensDestination.Memory), playDestinations)
    }

    @Test
    fun testNewConversationLabel() {
        assertEquals("New chat", WorkPlayLens.newConversationLabel(WorkPlayMode.Work))
        assertEquals("New scene", WorkPlayLens.newConversationLabel(WorkPlayMode.Play))
    }

    @Test
    fun testConversationsHeader() {
        assertEquals("Pinned", WorkPlayLens.conversationsHeader(WorkPlayMode.Work))
        assertEquals("Scenes", WorkPlayLens.conversationsHeader(WorkPlayMode.Play))
    }

    @Test
    fun testComposerPlaceholder() {
        // Work mode
        assertEquals("Message Alice…", WorkPlayLens.composerPlaceholder(WorkPlayMode.Work, "Alice"))
        assertEquals("Message the agent…", WorkPlayLens.composerPlaceholder(WorkPlayMode.Work, ""))
        assertEquals("Message the agent…", WorkPlayLens.composerPlaceholder(WorkPlayMode.Work, "  "))

        // Play mode
        assertEquals("Message the scene…", WorkPlayLens.composerPlaceholder(WorkPlayMode.Play, "Alice"))
        assertEquals("Message the scene…", WorkPlayLens.composerPlaceholder(WorkPlayMode.Play, ""))
    }

    @Test
    fun testAgentNoun() {
        assertEquals("agent", WorkPlayLens.agentNoun(WorkPlayMode.Work))
        assertEquals("character", WorkPlayLens.agentNoun(WorkPlayMode.Play))
    }
}
