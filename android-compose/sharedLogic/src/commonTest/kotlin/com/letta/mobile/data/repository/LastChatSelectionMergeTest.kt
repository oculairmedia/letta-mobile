package com.letta.mobile.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-etib9: merge policy for the persisted chat selection. */
class LastChatSelectionMergeTest {

    private val resolved = LastChatSelection(
        agentId = "agent-1",
        agentName = "PM-letta-mobile",
        conversationId = "conv-1",
    )

    @Test
    fun blankNameCarriesForwardForSameAgent() {
        for (blank in listOf(null, "", "   ")) {
            val merged = mergeLastChatSelection(
                previous = resolved,
                agentId = "agent-1",
                agentName = blank,
                conversationId = "conv-1",
            )
            assertEquals("PM-letta-mobile", merged?.agentName, "blank=<$blank>")
        }
    }

    @Test
    fun blankNameDoesNotLeakAcrossAgents() {
        val merged = mergeLastChatSelection(
            previous = resolved,
            agentId = "agent-2",
            agentName = null,
            conversationId = "conv-2",
        )
        assertEquals("agent-2", merged?.agentId)
        assertNull(merged?.agentName)
    }

    @Test
    fun resolvedNameOverwritesPreviousName() {
        val merged = mergeLastChatSelection(
            previous = resolved,
            agentId = "agent-1",
            agentName = "Renamed",
            conversationId = "conv-1",
        )
        assertEquals("Renamed", merged?.agentName)
    }

    @Test
    fun nameIsStoredWhenThereIsNoPreviousSelection() {
        val merged = mergeLastChatSelection(
            previous = null,
            agentId = "agent-1",
            agentName = "PM-letta-mobile",
            conversationId = null,
        )
        assertEquals("PM-letta-mobile", merged?.agentName)
        assertNull(merged?.conversationId)
    }

    @Test
    fun blankAgentIdIsNotAValidSelection() {
        assertNull(
            mergeLastChatSelection(
                previous = resolved,
                agentId = "   ",
                agentName = "PM-letta-mobile",
                conversationId = "conv-1",
            ),
        )
    }

    @Test
    fun blankConversationIdIsNormalizedToNull() {
        val merged = mergeLastChatSelection(
            previous = null,
            agentId = "agent-1",
            agentName = "PM-letta-mobile",
            conversationId = "  ",
        )
        assertNull(merged?.conversationId)
    }
}
