package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdFormatsTest {
    @Test
    fun defaultConversationIdIsPrefixPlusAgentId() {
        assertEquals("conv-default-agent-abc", IdFormats.defaultConversationIdFor("agent-abc"))
    }

    @Test
    fun bothDefaultConversationFormsAreRecognised() {
        assertTrue(IdFormats.isDefaultConversation("default"))
        assertTrue(IdFormats.isDefaultConversation("conv-default-agent-abc"))
        assertTrue(IdFormats.isDefaultConversation(ConversationId("conv-default-agent-abc")))
    }

    @Test
    fun ordinaryConversationsAreNotDefault() {
        assertFalse(IdFormats.isDefaultConversation("conv-123"))
        assertFalse(IdFormats.isDefaultConversation("defaults"))
        assertFalse(IdFormats.isDefaultConversation("my-conv-default-x"))
        assertFalse(IdFormats.isDefaultConversation(""))
        assertFalse(IdFormats.isDefaultConversation(null))
    }

    @Test
    fun prefixCheckExcludesTheBareDefault() {
        assertTrue(IdFormats.hasDefaultConversationPrefix("conv-default-agent-abc"))
        assertFalse(IdFormats.hasDefaultConversationPrefix("default"))
        assertFalse(IdFormats.hasDefaultConversationPrefix(null))
    }

    @Test
    fun optimisticPrefixesAreClientAndComposer() {
        assertEquals(listOf("client-", "cm-"), IdFormats.OPTIMISTIC_PREFIXES)
    }

    @Test
    fun optimisticMessageIdsAreRecognised() {
        assertTrue(IdFormats.isOptimisticMessageId("client-123"))
        assertTrue(IdFormats.isOptimisticMessageId("cm-stream-run-1"))
        assertTrue(IdFormats.isOptimisticMessageId("cm-reason-run-1"))
        assertTrue(IdFormats.isOptimisticMessageId(MessageId("client-user-initial-duplicate-1")))
    }

    @Test
    fun serverMessageIdsAreNotOptimistic() {
        assertFalse(IdFormats.isOptimisticMessageId("message-123"))
        assertFalse(IdFormats.isOptimisticMessageId("my-client-1"))
        assertFalse(IdFormats.isOptimisticMessageId("cmx-1"))
        assertFalse(IdFormats.isOptimisticMessageId(""))
        assertFalse(IdFormats.isOptimisticMessageId(null))
    }

    @Test
    fun stripAgentPrefixDropsOneLeadingPrefixOnly() {
        assertEquals("abc", IdFormats.stripAgentPrefix("agent-abc"))
        assertEquals("agent-abc", IdFormats.stripAgentPrefix("agent-agent-abc"))
        assertEquals("abc", IdFormats.stripAgentPrefix(AgentId("agent-abc")))
    }

    @Test
    fun stripAgentPrefixLeavesOtherIdsUnchanged() {
        assertEquals("local-abc", IdFormats.stripAgentPrefix("local-abc"))
        assertEquals("my-agent-abc", IdFormats.stripAgentPrefix("my-agent-abc"))
        assertEquals("", IdFormats.stripAgentPrefix(""))
    }
}
