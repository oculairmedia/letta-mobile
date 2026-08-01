package com.letta.mobile.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * letta-mobile-byqjj.1: atomic persistence of the last chat selection.
 *
 * Backtick test names deliberately contain no `()` — legal on JVM, illegal on
 * Kotlin/Native, and this suite runs under `:sharedLogic:allTests`.
 */
class LastChatSelectionStorageTest {

    private val selection = LastChatSelection(
        agentId = "agent-1",
        agentName = "PM-letta-mobile",
        conversationId = "conv-1",
    )

    @Test
    fun `round trip preserves the whole triple`() {
        val stored = LastChatSelectionStorage.serialize(selection)
        assertNotNull(stored)
        assertEquals(selection, LastChatSelectionStorage.deserialize(stored))
    }

    @Test
    fun `the whole triple lives in one string`() {
        val stored = LastChatSelectionStorage.serialize(selection)
        assertNotNull(stored)
        // If any field were persisted separately this would not hold, and a
        // torn triple would be representable again.
        assertEquals(true, stored.contains("agent-1"))
        assertEquals(true, stored.contains("PM-letta-mobile"))
        assertEquals(true, stored.contains("conv-1"))
    }

    @Test
    fun `round trip preserves a selection with no name and no conversation`() {
        val bare = LastChatSelection(agentId = "agent-1")
        val stored = LastChatSelectionStorage.serialize(bare)
        assertNotNull(stored)
        assertEquals(bare, LastChatSelectionStorage.deserialize(stored))
    }

    @Test
    fun `missing stored value is absent`() {
        assertNull(LastChatSelectionStorage.deserialize(null))
        assertNull(LastChatSelectionStorage.deserialize(""))
        assertNull(LastChatSelectionStorage.deserialize("   "))
    }

    @Test
    fun `malformed stored value is discarded rather than guessed`() {
        assertNull(LastChatSelectionStorage.deserialize("not json"))
        assertNull(LastChatSelectionStorage.deserialize("{\"agentName\":\"PM\"}"))
    }

    // --- identity fence -----------------------------------------------------

    @Test
    fun `blank agentId invalidates the record`() {
        for (blank in listOf("", "   ")) {
            assertNull(
                LastChatSelectionStorage.serialize(
                    LastChatSelection(
                        agentId = blank,
                        agentName = "PM-letta-mobile",
                        conversationId = "conv-1",
                    ),
                ),
                "blank=<$blank>",
            )
        }
    }

    @Test
    fun `a stored record with a blank agentId is rejected on read`() {
        val orphaned =
            "{\"agentId\":\"\",\"agentName\":\"PM-letta-mobile\",\"conversationId\":\"conv-1\"}"
        assertNull(LastChatSelectionStorage.deserialize(orphaned))
    }

    @Test
    fun `blank conversationId normalizes to absent`() {
        val stored = LastChatSelectionStorage.serialize(
            LastChatSelection(
                agentId = "agent-1",
                agentName = "PM-letta-mobile",
                conversationId = "   ",
            ),
        )
        assertNull(LastChatSelectionStorage.deserialize(stored)?.conversationId)
    }

    @Test
    fun `blank agentName normalizes to absent`() {
        val stored = LastChatSelectionStorage.serialize(
            LastChatSelection(
                agentId = "agent-1",
                agentName = "  ",
                conversationId = "conv-1",
            ),
        )
        assertNull(LastChatSelectionStorage.deserialize(stored)?.agentName)
    }

    @Test
    fun `a name is never carried across an agentId change`() {
        val previous = LastChatSelectionStorage.deserialize(
            LastChatSelectionStorage.serialize(selection),
        )
        val next = mergeLastChatSelection(
            previous = previous,
            agentId = "agent-2",
            agentName = null,
            conversationId = "conv-2",
        )
        val reloaded = LastChatSelectionStorage.deserialize(
            LastChatSelectionStorage.serialize(assertNotNull(next)),
        )
        assertEquals("agent-2", reloaded?.agentId)
        assertNull(reloaded?.agentName, "agent-1's name must not label agent-2")
    }

    // --- legacy migration ---------------------------------------------------

    @Test
    fun `a legacy triple whose consistency cannot be proven is discarded`() {
        assertNull(
            LastChatSelectionStorage.migrateLegacy(
                legacyAgentId = "agent-1",
                legacyAgentName = "PM-letta-mobile",
                legacyConversationId = "conv-1",
            ),
        )
    }

    @Test
    fun `a legacy triple torn across agents is discarded rather than reconstructed`() {
        // agentId was updated to agent-2 but the name write for agent-1 stuck.
        assertNull(
            LastChatSelectionStorage.migrateLegacy(
                legacyAgentId = "agent-2",
                legacyAgentName = "agent-1-display-name",
                legacyConversationId = "agent-1-conversation",
            ),
        )
    }

    @Test
    fun `a legacy agentId with no satellites migrates intact`() {
        val migrated = LastChatSelectionStorage.migrateLegacy(
            legacyAgentId = "agent-1",
            legacyAgentName = null,
            legacyConversationId = null,
        )
        assertEquals(LastChatSelection(agentId = "agent-1"), migrated)
    }

    @Test
    fun `a legacy triple proven consistent migrates whole`() {
        val migrated = LastChatSelectionStorage.migrateLegacy(
            legacyAgentId = "agent-1",
            legacyAgentName = "PM-letta-mobile",
            legacyConversationId = "conv-1",
            legacyConsistent = true,
        )
        assertEquals(selection, migrated)
    }

    @Test
    fun `legacy satellites without an agentId are discarded`() {
        assertNull(
            LastChatSelectionStorage.migrateLegacy(
                legacyAgentId = null,
                legacyAgentName = "PM-letta-mobile",
                legacyConversationId = "conv-1",
            ),
        )
        assertNull(
            LastChatSelectionStorage.migrateLegacy(
                legacyAgentId = "   ",
                legacyAgentName = "PM-letta-mobile",
                legacyConversationId = "conv-1",
            ),
        )
    }

    @Test
    fun `an entirely empty legacy layout is simply absent`() {
        assertNull(
            LastChatSelectionStorage.migrateLegacy(
                legacyAgentId = null,
                legacyAgentName = null,
                legacyConversationId = null,
            ),
        )
    }

    @Test
    fun `a migrated legacy selection round trips through the new format`() {
        val migrated = assertNotNull(
            LastChatSelectionStorage.migrateLegacy(
                legacyAgentId = "agent-1",
                legacyAgentName = "PM-letta-mobile",
                legacyConversationId = "conv-1",
                legacyConsistent = true,
            ),
        )
        val stored = LastChatSelectionStorage.serialize(migrated)
        assertEquals(migrated, LastChatSelectionStorage.deserialize(stored))
    }
}
