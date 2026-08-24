package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkingDirectoryScopeKeyTest {

    @Test
    fun `real conversation id scopes by conversation alone`() {
        assertEquals(
            "conversation:conv-123",
            WorkingDirectoryScopeKey.of(agentId = "agent-1", conversationId = "conv-123"),
        )
    }

    @Test
    fun `real conversation id ignores agent id even when agent is null`() {
        assertEquals(
            "conversation:conv-123",
            WorkingDirectoryScopeKey.of(agentId = null, conversationId = "conv-123"),
        )
    }

    @Test
    fun `default conversation id scopes by agent`() {
        assertEquals(
            "agent:agent-1::conversation:default",
            WorkingDirectoryScopeKey.of(agentId = "agent-1", conversationId = "default"),
        )
    }

    @Test
    fun `null conversation id normalizes to default`() {
        assertEquals(
            "agent:agent-1::conversation:default",
            WorkingDirectoryScopeKey.of(agentId = "agent-1", conversationId = null),
        )
    }

    @Test
    fun `blank conversation id normalizes to default`() {
        assertEquals(
            "agent:agent-1::conversation:default",
            WorkingDirectoryScopeKey.of(agentId = "agent-1", conversationId = ""),
        )
    }

    @Test
    fun `default conversation with no agent id falls back to unknown marker`() {
        assertEquals(
            "agent:__unknown__::conversation:default",
            WorkingDirectoryScopeKey.of(agentId = null, conversationId = "default"),
        )
    }

    @Test
    fun `default conversation with blank agent id falls back to unknown marker`() {
        assertEquals(
            "agent:__unknown__::conversation:default",
            WorkingDirectoryScopeKey.of(agentId = "", conversationId = "default"),
        )
    }
}
