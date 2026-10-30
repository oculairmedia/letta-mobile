package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationReadTargetTest {
    @Test
    fun resolvesDefaultAliasWithOwner() {
        assertEquals(
            ConversationId("default") to AgentId("agent-child"),
            resolveConversationReadTarget(ConversationId("conv-default-agent-child")),
        )
    }

    @Test
    fun rejectsAmbiguousBareDefault() {
        assertNull(resolveConversationReadTarget(ConversationId("default")))
        assertNull(resolveConversationReadTarget(ConversationId("")))
    }

    @Test
    fun preservesOrdinaryAndLocalConversations() {
        for (id in listOf("conv-12345678-1234-1234-1234-123456789abc", "local-conv-child", "conv-default-not-an-agent")) {
            assertEquals(ConversationId(id) to null, resolveConversationReadTarget(ConversationId(id)))
        }
    }
}
