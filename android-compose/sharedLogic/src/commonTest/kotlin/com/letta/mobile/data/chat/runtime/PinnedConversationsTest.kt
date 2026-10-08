package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-bzvro.17 (F17): pins survive a restart, sort first, and follow a fork. */
class PinnedConversationsTest {
    @Test
    fun pinsSurviveARestart() {
        val store = MapSettingsStore()
        PinnedConversations(store).apply {
            setPinned("conv-b", true)
            setPinned("conv-a", true)
        }

        val restarted = PinnedConversations(store)

        assertEquals(setOf("conv-a", "conv-b"), restarted.pinned.value)
    }

    @Test
    fun unpinningTheLastPinClearsTheKey() {
        val store = MapSettingsStore()
        val pins = PinnedConversations(store)
        pins.toggle("conv-a")
        assertTrue(pins.isPinned("conv-a"))
        pins.toggle("conv-a")

        assertFalse(pins.isPinned("conv-a"))
        assertNull(store.getString(PinnedConversations.DEFAULT_KEY))
    }

    @Test
    fun pinnedConversationsSortFirstAndEachGroupKeepsItsOrder() {
        val recency = listOf("c1", "c2", "c3", "c4", "c5")
        assertEquals(listOf("c2", "c4", "c1", "c3", "c5"), PinnedConversations.pinnedFirst(recency, setOf("c4", "c2")) { it })
        assertEquals(recency, PinnedConversations.pinnedFirst(recency, emptySet()) { it })
    }

    @Test
    fun aForkOfAPinnedConversationIsPinned() {
        val pins = PinnedConversations(MapSettingsStore())
        pins.setPinned("source", true)

        pins.inherit("source", "fork")
        pins.inherit("other", "fork-2")

        assertEquals(setOf("source", "fork"), pins.pinned.value)
    }

    @Test
    fun aBlankIdIsNeverPinned() {
        val pins = PinnedConversations(MapSettingsStore())
        pins.setPinned(" ", true)
        assertEquals(emptySet(), pins.pinned.value)
    }
}

internal class MapSettingsStore : SecureSettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() = values.clear()
}
