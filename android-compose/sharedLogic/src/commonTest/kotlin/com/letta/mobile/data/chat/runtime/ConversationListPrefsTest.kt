package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.5 (G37): settle, markers and auto-pin are client marks that survive a restart. */
class ConversationListPrefsTest {
    private val star = ConversationListPrefs.MARKER_CHOICES[0]
    private val bug = ConversationListPrefs.MARKER_CHOICES[3]
    private val convA = ConversationId("conv-a")
    private val convB = ConversationId("conv-b")

    @Test
    fun settledChatsAndMarkersSurviveARestart() {
        val store = MapSettingsStore()
        ConversationListPrefs(store).apply {
            setSettled(convA, true)
            setMarker(convA, ConversationMarker(star))
            setMarker(convB, ConversationMarker(bug))
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
        prefs.setSettled(convA, true)
        prefs.setMarker(convA, ConversationMarker(star))

        prefs.setSettled(convA, false)
        prefs.setMarker(convA, null)

        assertEquals(emptySet(), prefs.settled.value)
        assertEquals(emptyMap(), prefs.markers.value)
        assertNull(store.getString("conversations.list.settled"))
        assertNull(store.getString("conversations.list.markers"))
    }

    @Test
    fun aMarkerOutsideTheChoicesIsNotStored() {
        val prefs = ConversationListPrefs(MapSettingsStore())
        prefs.setMarker(convA, ConversationMarker("not-an-emoji"))
        assertEquals(emptyMap(), prefs.markers.value)
    }

    @Test
    fun aNewChatIsPinnedOnlyWhileAutoPinIsOn() {
        val prefs = ConversationListPrefs(MapSettingsStore())
        val pinned = mutableListOf<ConversationId>()

        prefs.onConversationCreated(ConversationId("conv-1"), pinned::add)
        assertFalse(prefs.autoPinNewChats.value)
        prefs.setAutoPinNewChats(true)
        prefs.onConversationCreated(ConversationId("conv-2"), pinned::add)

        assertEquals(listOf(ConversationId("conv-2")), pinned)
    }

    @Test
    fun forgettingAChatDropsItsMarks() {
        val prefs = ConversationListPrefs(MapSettingsStore())
        prefs.setSettled(convA, true)
        prefs.setMarker(convA, ConversationMarker(star))

        prefs.forget(convA)

        assertEquals(emptySet(), prefs.settled.value)
        assertEquals(emptyMap(), prefs.markers.value)
    }

    @Test
    fun pinnedLeadSettledTrailAndEachGroupKeepsItsOrder() {
        val recency = listOf("c1", "c2", "c3", "c4", "c5")
        val arranged = ConversationListOrder.arrange(recency, ConversationListGroups(pinned = setOf("c4"), settled = setOf("c1", "c4", "c3"))) { it }
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
