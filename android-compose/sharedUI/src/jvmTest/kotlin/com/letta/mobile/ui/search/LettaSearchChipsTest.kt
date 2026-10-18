package com.letta.mobile.ui.search

import com.letta.mobile.data.search.MessageSearchCapabilities
import com.letta.mobile.data.search.MessageSearchMode
import com.letta.mobile.data.search.MessageSearchSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.5: the shared search hides mode chips when the host only has full-text. */
class LettaSearchChipsTest {
    @Test
    fun aLocalIndexShowsRolesAndHidesModes() {
        val rows = messageSearchChipRows(MessageSearchCapabilities.LOCAL, MessageSearchSelection()) {}
        assertEquals(1, rows.size)
        assertEquals(listOf("user", "assistant", "reasoning", "tool"), rows.single().chips.map { it.id })
    }

    @Test
    fun aRemoteHostShowsEveryModeThenRoles() {
        val rows = messageSearchChipRows(MessageSearchCapabilities.REMOTE, MessageSearchSelection()) {}
        assertEquals(listOf("hybrid", "vector", "fts"), rows.first().chips.map { it.id })
        // Letta's search returns no tool traffic, so a backend host offers no Tool chip.
        assertEquals(listOf("user", "assistant", "reasoning"), rows.last().chips.map { it.id })
        assertTrue(rows.first().exclusive)
        assertEquals(MessageSearchMode.Hybrid.label, rows.first().chips.first().label)
    }

    @Test
    fun noMessageSearchHidesTheChips() {
        assertTrue(messageSearchChipRows(MessageSearchCapabilities.NONE, MessageSearchSelection()) {}.isEmpty())
    }
}
