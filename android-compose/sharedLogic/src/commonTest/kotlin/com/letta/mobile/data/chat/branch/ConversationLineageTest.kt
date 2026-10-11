package com.letta.mobile.data.chat.branch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-7mys7 (G37): a fork remembers the conversation it came from, across restarts. */
class ConversationLineageTest {
    @Test
    fun aRecordedParentSurvivesARestart() {
        val store = ConversationBranchControllerTest.MapStore()
        ConversationLineage(store).record("fork-1", ConversationParent("conv-1", BranchKind.Rerun))

        val reloaded = ConversationLineage(store)

        assertEquals(ConversationParent("conv-1", BranchKind.Rerun), reloaded.parentOf("fork-1"))
        assertEquals(mapOf("fork-1" to ConversationParent("conv-1", BranchKind.Rerun)), reloaded.parents.value)
    }

    @Test
    fun aConversationIsNeverItsOwnParentAndForgettingClearsTheStore() {
        val store = ConversationBranchControllerTest.MapStore()
        val lineage = ConversationLineage(store)

        lineage.record("conv-1", ConversationParent("conv-1"))
        assertNull(lineage.parentOf("conv-1"))

        lineage.record("fork-1", ConversationParent("conv-1"))
        lineage.forget("fork-1")

        assertNull(lineage.parentOf("fork-1"))
        assertEquals(emptyMap(), store.values)
    }

    @Test
    fun aMalformedLineIsSkipped() {
        val store = ConversationBranchControllerTest.MapStore()
        store.values[ConversationLineage.DEFAULT_KEY] = "fork-1\tconv-1\tFork\nbroken\n\tconv-2\nfork-3\tconv-3\tUnknownKind"

        val lineage = ConversationLineage(store)

        assertEquals(setOf("fork-1", "fork-3"), lineage.parents.value.keys)
        assertEquals(BranchKind.Fork, lineage.parentOf("fork-3")?.kind)
    }
}
