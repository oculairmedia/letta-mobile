package com.letta.mobile.data.chat.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.5 (G37): settle, markers and auto-pin are client marks that survive a restart. */
class ConversationListPrefsTest {
    private val star = ConversationListPrefs.MARKER_CHOICES[0]
    private val bug = ConversationListPrefs.MARKER_CHOICES[3]

    @Test
    fun settledChatsAndMarkersSurviveARestart() {
        val store = MapSettingsStore()
        ConversationListPrefs(store).apply {
            setSettled("conv-a", true)
            setMarker("conv-a", star)
            setMarker("conv-b", bug)
            setAutoPinNewChats(true)
        }

        val restarted = ConversationListPrefs(store)

        assertEquals(setOf("conv-a"), restarted.settled.value)
        assertEquals(mapOf("conv-a" to star, "conv-b" to bug), restarted.markers.value)
        assertTrue(restarted.autoPinNewChats.value)
    }

    @Test
    fun moveToActiveAndClearingAMarkerDropTheirKeys() {
        val store = MapSettingsStore()
        val prefs = ConversationListPrefs(store)
        prefs.setSettled("conv-a", true)
        prefs.setMarker("conv-a", star)

        prefs.setSettled("conv-a", false)
        prefs.setMarker("conv-a", null)

        assertEquals(emptySet(), prefs.settled.value)
        assertEquals(emptyMap(), prefs.markers.value)
        assertNull(store.getString("conversations.list.settled"))
        assertNull(store.getString("conversations.list.markers"))
    }

    @Test
    fun aMarkerOutsideTheChoicesIsNotStored() {
        val prefs = ConversationListPrefs(MapSettingsStore())
        prefs.setMarker("conv-a", "not-an-emoji")
        assertEquals(emptyMap(), prefs.markers.value)
    }

    @Test
    fun aNewChatIsPinnedOnlyWhileAutoPinIsOn() {
        val prefs = ConversationListPrefs(MapSettingsStore())
        val pinned = mutableListOf<String>()

        prefs.onConversationCreated("conv-1", pinned::add)
        assertFalse(prefs.autoPinNewChats.value)
        prefs.setAutoPinNewChats(true)
        prefs.onConversationCreated("conv-2", pinned::add)

        assertEquals(listOf("conv-2"), pinned)
    }

    @Test
    fun forgettingAChatDropsItsMarks() {
        val prefs = ConversationListPrefs(MapSettingsStore())
        prefs.setSettled("conv-a", true)
        prefs.setMarker("conv-a", star)

        prefs.forget("conv-a")

        assertEquals(emptySet(), prefs.settled.value)
        assertEquals(emptyMap(), prefs.markers.value)
    }

    @Test
    fun pinnedLeadSettledTrailAndEachGroupKeepsItsOrder() {
        val recency = listOf("c1", "c2", "c3", "c4", "c5")
        val arranged = ConversationListOrder.arrange(recency, pinned = setOf("c4"), settled = setOf("c1", "c4", "c3")) { it }
        assertEquals(listOf("c4", "c2", "c5", "c1", "c3"), arranged)
    }

    @Test
    fun nextAndPreviousStepThroughTheListAndStopAtItsEnds() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", ConversationNavigation.adjacent(ids, "a", ConversationStep.Next))
        assertEquals("a", ConversationNavigation.adjacent(ids, "b", ConversationStep.Previous))
        assertNull(ConversationNavigation.adjacent(ids, "c", ConversationStep.Next))
        assertNull(ConversationNavigation.adjacent(ids, "a", ConversationStep.Previous))
        assertEquals("a", ConversationNavigation.adjacent(ids, null, ConversationStep.Next))
        assertEquals("c", ConversationNavigation.adjacent(ids, "gone", ConversationStep.Previous))
        assertNull(ConversationNavigation.adjacent(emptyList(), "a", ConversationStep.Next))
    }
}
